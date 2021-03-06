package org.ovirt.engine.ui.uicommonweb.models;

import org.ovirt.engine.core.common.action.ActionType;
import org.ovirt.engine.core.common.action.UserProfileParameters;
import org.ovirt.engine.core.common.businessentities.UserProfile;
import org.ovirt.engine.ui.frontend.Frontend;
import org.ovirt.engine.ui.uicommonweb.UICommand;
import org.ovirt.engine.ui.uicommonweb.dataprovider.AsyncDataProvider;
import org.ovirt.engine.ui.uicompat.ConstantsManager;
import org.ovirt.engine.ui.uicompat.UIConstants;

public class OptionsModel extends EntityModel<EditOptionsModel> {

    private static final UIConstants constants = ConstantsManager.getInstance().getConstants();

    private UICommand editCommand;

    private UserProfile userProfile;

    public OptionsModel() {
        setEditCommand(new UICommand(constants.edit(), this));
    }

    @Override
    public void executeCommand(UICommand command) {
        super.executeCommand(command);
        if (constants.edit().equalsIgnoreCase(command.getName())) {
            onEdit();
        } else if (constants.ok().equalsIgnoreCase(command.getName())) {
            onSave();
        } else if (constants.cancel().equalsIgnoreCase(command.getName())) {
            cancel();
        }
    }

    private void onEdit() {
        if (getWindow() != null) {
            return;
        }

        final EditOptionsModel model = new EditOptionsModel();

        model.setTitle(constants.editOptionsTitle());

        model.setHashName("edit_options"); //$NON-NLS-1$
        setWindow(model);

        UICommand okCommand = UICommand.createDefaultOkUiCommand(constants.ok(), this);
        model.getCommands().add(okCommand);
        UICommand cancelCommand = UICommand.createCancelUiCommand(constants.cancel(), this);
        model.getCommands().add(cancelCommand);

        AsyncDataProvider.getInstance().getUserProfile(model.asyncQuery(returnValue -> {
            UserProfile profile = returnValue.getReturnValue();
            if (profile != null) {
                setUserProfile(profile);
                model.getPublicKey().setEntity(profile.getSshPublicKey());
            }
        }));
    }

    private void onSave() {
        EditOptionsModel model = (EditOptionsModel) getWindow();
        UserProfileParameters params = new UserProfileParameters();
        ActionType action = ActionType.AddUserProfile;
        if (getUserProfile() != null) {
            action = ActionType.UpdateUserProfile;
            params.setUserProfile(getUserProfile());
        }
        params.getUserProfile().setSshPublicKey(model.getPublicKey().getEntity());
        model.startProgress(null);
        Frontend.getInstance().runAction(action, params, result -> {
            EditOptionsModel editOptionsModel = (EditOptionsModel) result.getState();
            editOptionsModel.stopProgress();
            cancel();
        }, model);
    }

    public UICommand getEditCommand() {
        return editCommand;
    }

    public void setEditCommand(UICommand editCommand) {
        this.editCommand = editCommand;
        getCommands().add(editCommand);
    }

    protected void cancel() {
        setWindow(null);
    }

    public UserProfile getUserProfile() {
        return userProfile;
    }

    public void setUserProfile(UserProfile userProfile) {
        this.userProfile = userProfile;
    }
}
