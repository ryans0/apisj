package org.apis.gui.controller.popup;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.InputEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.FlowPane;
import org.apis.db.sql.AddressGroupRecord;
import org.apis.db.sql.DBManager;
import org.apis.gui.controller.module.ApisTagItemController;
import org.apis.gui.controller.base.BasePopupController;
import org.apis.gui.manager.PopupManager;
import org.apis.gui.manager.StringManager;
import org.apis.gui.model.MyAddressModel;
import org.apis.gui.model.base.BaseModel;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

public class PopupMyAddressGroupController extends BasePopupController {

    @FXML private AnchorPane rootPane;
    @FXML private FlowPane list;
    @FXML private TextField groupText;
    @FXML private Label titleLabel, subTitleLabel, addGroupLabel, noBtn, addBtn;

    private ArrayList<String> textGroupList = new ArrayList<>();
    private MyAddressModel model;
    private boolean isEdit = false;

    public void exit(){
        if(isEdit) {
            PopupMyAddressEditController controller = (PopupMyAddressEditController)PopupManager.getInstance().showMainPopup(rootPane, "popup_my_address_edit.fxml", 1);
            controller.setMyAddressHandler(this.myAddressHandler);
            controller.setModel(model);
        }else{
            PopupMyAddressRegisterController controller = (PopupMyAddressRegisterController)PopupManager.getInstance().showMainPopup(rootPane,"popup_my_address_register.fxml", 1);
            controller.setMyAddressHandler(this.myAddressHandler);
            controller.setModel(model);
        }
        parentRequestFocus();
    }
    @Override
    public void initialize(URL location, ResourceBundle resources) {
        languageSetting();
        initGroupList();
    }

    private void languageSetting(){
        titleLabel.textProperty().bind(StringManager.getInstance().myAddress.addGroupTitle);
        subTitleLabel.textProperty().bind(StringManager.getInstance().myAddress.addGroupSubTitle);
        addGroupLabel.textProperty().bind(StringManager.getInstance().myAddress.addGroupTitle);
        noBtn.textProperty().bind(StringManager.getInstance().common.backButton);
        addBtn.textProperty().bind(StringManager.getInstance().common.addButton);
        groupText.promptTextProperty().bind(StringManager.getInstance().myAddress.groupPlaceHolder);
    }

    @FXML
    public void onMouseClicked(InputEvent event){
        String id = ((Node)event.getSource()).getId();
        if(id.equals("addBtn")){
            if(groupText.getText().trim().length() > 0) {

                DBManager.getInstance().updateAddressGroup(groupText.getText().trim());
                initGroupList();

                groupText.setText("");

                exit();
            }
        }
    }

    private void initGroupList(){
        textGroupList = new ArrayList<>();
        list.getChildren().clear();
        List<AddressGroupRecord> groups = DBManager.getInstance().selectAddressGroups();
        for(int i=0; i<groups.size(); i++){
            textGroupList.add(groups.get(i).getGroupName());
            addList(groups.get(i).getGroupName());
        }
    }

    private void addList(String text){

        try {
            URL labelUrl = getClass().getClassLoader().getResource("scene/module/apis_tag_item.fxml");

            //item
            FXMLLoader loader = new FXMLLoader(labelUrl);
            Label label = loader.load();
            //label.setMaxWidth( (MAX_WIDTH - 10) / 2 + 20 );
            list.getChildren().add(label);

            ApisTagItemController itemController = (ApisTagItemController)loader.getController();
            itemController.setState(ApisTagItemController.STATE_VIEW_NORAML);
            itemController.setText(text);

            itemController.setState(ApisTagItemController.STATE_SETTING_NORAML);
            itemController.setHandle(new ApisTagItemController.ApisTagItemImpl() {
                @Override
                public void onMouseClicked(String text) {

                    // delete group
                    DBManager.getInstance().deleteAddressGroup(text);
                    initGroupList();

                }
            });

        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    public void setModel(BaseModel model, boolean isEdit) {
        this.model = (MyAddressModel)model;
        this.isEdit = isEdit;
    }

    private PopupMyAddressController.PopupMyAddressImpl myAddressHandler;
    public void setMyAddressHandler(PopupMyAddressController.PopupMyAddressImpl myAddressHandler) {
        this.myAddressHandler = myAddressHandler;
    }
}
