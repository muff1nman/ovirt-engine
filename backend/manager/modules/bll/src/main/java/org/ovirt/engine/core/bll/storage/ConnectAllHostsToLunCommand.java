package org.ovirt.engine.core.bll.storage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.ovirt.engine.core.bll.Backend;
import org.ovirt.engine.core.common.AuditLogType;
import org.ovirt.engine.core.common.action.ExtendSANStorageDomainParameters;
import org.ovirt.engine.core.common.action.VdcReturnValueBase;
import org.ovirt.engine.core.common.businessentities.LUNs;
import org.ovirt.engine.core.common.businessentities.VDS;
import org.ovirt.engine.core.common.businessentities.VdsSpmStatus;
import org.ovirt.engine.core.common.errors.VdcBLLException;
import org.ovirt.engine.core.common.utils.Pair;
import org.ovirt.engine.core.common.vdscommands.GetDeviceListVDSCommandParameters;
import org.ovirt.engine.core.common.vdscommands.GetDevicesVisibilityVDSCommandParameters;
import org.ovirt.engine.core.common.vdscommands.VDSCommandType;
import org.ovirt.engine.core.compat.Guid;
import org.ovirt.engine.core.utils.linq.LinqUtils;
import org.ovirt.engine.core.utils.linq.Predicate;
import org.ovirt.engine.core.utils.log.Log;
import org.ovirt.engine.core.utils.log.LogFactory;

@SuppressWarnings("serial")
public class ConnectAllHostsToLunCommand<T extends ExtendSANStorageDomainParameters> extends
        StorageDomainCommandBase<T> {

    public ConnectAllHostsToLunCommand(T parameters) {
        super(parameters);
    }

    public static class ConnectAllHostsToLunCommandReturnValue extends VdcReturnValueBase {
        private VDS failedVds;
        private LUNs failedLun;

        public VDS getFailedVds() {
            return failedVds;
        }

        public void setFailedVds(VDS failedVds) {
            this.failedVds = failedVds;
        }

        public LUNs getFailedLun() {
            return failedLun;
        }

        public void setFailedLun(LUNs failedLun) {
            this.failedLun = failedLun;
        }
    }

    @Override
    protected VdcReturnValueBase createReturnValue() {
        return new ConnectAllHostsToLunCommandReturnValue();
    }

    private ConnectAllHostsToLunCommandReturnValue getResult() {
        return (ConnectAllHostsToLunCommandReturnValue) getReturnValue();
    }

    @Override
    protected void executeCommand() {
        VDS spmVds = LinqUtils.first(LinqUtils.filter(getAllRunningVdssInPool(), new Predicate<VDS>() {
            @Override
            public boolean eval(VDS vds) {
                return vds.getspm_status() == VdsSpmStatus.SPM;
            }
        }));

        final List<LUNs> luns = getHostLuns(spmVds);
        final Map<String, LUNs> lunsMap = new HashMap<String, LUNs>();
        for (LUNs lun : luns) {
            lunsMap.put(lun.getLUN_id(), lun);
        }
        final List<LUNs> processedLunsList = new ArrayList<LUNs>();
        for (String lunId : getParameters().getLunIds()) {
            LUNs lun = lunsMap.get(lunId);
            if (lun == null) {
                //fail
                final ConnectAllHostsToLunCommandReturnValue result = getResult();
                result.setFailedVds(spmVds);
                result.setFailedLun(getDbFacade().getLunDao()
                        .get(lunId));
                return;
            }

            lun.setvolume_group_id(getStorageDomain().getstorage());
            processedLunsList.add(lun);
        }
        // connect all vds in pool (except spm) to lun and getDeviceList
        Pair<Boolean, Map<String, List<Guid>>> result = ConnectVdsToLun(processedLunsList);
        if (result.getFirst()) {
            getReturnValue().setActionReturnValue(processedLunsList);
            setCommandShouldBeLogged(false);
            setSucceeded(true);
        } else {
            // disconnect all hosts if connection is not in use by other luns
            Map<String, List<Guid>> processed = result.getSecond();
            for (String lunId : processed.keySet()) {
                for (Guid vdsId : processed.get(lunId)) {
                    LUNs lun = lunsMap.get(lunId);
                    StorageHelperDirector.getInstance().getItem(getStoragePool().getstorage_pool_type())
                            .DisconnectStorageFromLunByVdsId(getStorageDomain(), vdsId, lun);
                }
            }
        }
    }

    /**
     * The following method will connect all provided lund to all running host in pool
     *
     * @param luns
     *            - the luns which should be connected
     * @return the map where the key is true/false value which means if connection successes/not successes and value is
     *         map of luns Ids -> connected hosts
     */
    private Pair<Boolean, Map<String, List<Guid>>> ConnectVdsToLun(List<LUNs> luns) {
        Map<String, List<Guid>> resultMap = new HashMap<String, List<Guid>>();
        for (VDS vds : getAllRunningVdssInPool()) {
            // try to connect vds to luns and getDeviceList in order to refresh them
            for (LUNs lun : luns) {
                if (!connectStorageToLunByVdsId(vds, lun)) {
                    log.errorFormat("Could not connect host {0} to lun {1}", vds.getvds_name(), lun.getLUN_id());
                    setVds(vds);
                    final ConnectAllHostsToLunCommandReturnValue result = getResult();
                    result.setFailedVds(vds);
                    result.setFailedLun(lun);
                    return new Pair<Boolean, Map<String, List<Guid>>>(Boolean.FALSE, resultMap);
                } else {
                    List<Guid> hosts = resultMap.get(lun.getLUN_id());
                    if (hosts == null) {
                        hosts = new ArrayList<Guid>();
                        resultMap.put(lun.getLUN_id(), hosts);
                    }
                    hosts.add(vds.getId());
                }
            }
            // Refresh all connected luns to host
            if (!validateConnectedLuns(vds, getParameters().getLunIds())) {
                return new Pair<Boolean, Map<String, List<Guid>>>(Boolean.FALSE, resultMap);
            }
        }
        return new Pair<Boolean, Map<String, List<Guid>>>(Boolean.TRUE, resultMap);
    }

    private boolean connectStorageToLunByVdsId(VDS vds, LUNs lun) {
        try {
            return StorageHelperDirector.getInstance()
                    .getItem(getStorageDomain().getstorage_type())
                    .ConnectStorageToLunByVdsId(getStorageDomain(), vds.getId(), lun, Guid.Empty);
        } catch (VdcBLLException e) {
            final ConnectAllHostsToLunCommandReturnValue result = getResult();
            result.setFailedVds(vds);
            result.setFailedLun(lun);
            throw e;
        }
    }

    @SuppressWarnings("unchecked")
    private List<LUNs> getHostLuns(VDS vds) {
        try {
            return (List<LUNs>) runVdsCommand(
                    VDSCommandType.GetDeviceList,
                    new GetDeviceListVDSCommandParameters(vds.getId(),
                            getStorageDomain().getstorage_type())).getReturnValue();
        } catch (VdcBLLException e) {
            getResult().setFailedVds(vds);
            throw e;
        }
    }

    /**
     * The following method will check which luns were successfully connected to vds
     *
     * @param vds
     *            - the host
     * @param processedLunIds
     *            - luns ids which we wants to check
     * @return - true if all connections successes, false otherwise
     */
    private boolean validateConnectedLuns(VDS vds, List<String> processedLunIds) {
        @SuppressWarnings("unchecked")
        Map<String, Boolean> returnValue = (Map<String, Boolean>) Backend.getInstance()
                .getResourceManager()
                .RunVdsCommand(VDSCommandType.GetDevicesVisibility,
                        new GetDevicesVisibilityVDSCommandParameters(vds.getId(),
                                processedLunIds.toArray(new String[processedLunIds.size()]))).getReturnValue();
        for (Map.Entry<String, Boolean> returnValueEntry : returnValue.entrySet()) {
            if (!Boolean.TRUE.equals(returnValueEntry.getValue())) {
                return false;
            }
        }
        return true;
    }

    @Override
    public AuditLogType getAuditLogTypeValue() {
        // this should return only error, if command succeeded no logging is
        // required
        return AuditLogType.USER_CONNECT_HOSTS_TO_LUN_FAILED;
    }

    private static Log log = LogFactory.getLog(ConnectAllHostsToLunCommand.class);
}
