package org.apis.gui.controller;

import com.google.zxing.WriterException;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import org.apis.gui.common.IdenticonGenerator;
import org.apis.gui.manager.AppManager;
import org.apis.gui.model.SelectBoxDomainModel;
import org.apis.gui.model.SelectBoxWalletItemModel;
import org.apis.gui.model.WalletItemModel;
import org.apis.keystore.KeyStoreDataExp;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.ResourceBundle;

public class ApisSelectBoxController implements Initializable {
    public static final int SELECT_BOX_TYPE_ALIAS = 0;
    public static final int SELECT_BOX_TYPE_ADDRESS = 1;
    public static final int SELECT_BOX_TYPE_DOMAIN = 2;
    public static final int SELECT_BOX_TYPE_ONLY_ADDRESS = 3;
    private int selectBoxType = SELECT_BOX_TYPE_ALIAS;

    public static final int STAGE_DEFAULT = 0;
    public static final int STAGE_SELECTED = 1;

    private Node aliasHeaderNode;
    private Node addressHeaderNode;
    private Node domainHeaderNode;
    private Node onlyAddressHeaderNode;

    private ApisSelectBoxImpl handler;
    private ApisSelectBoxHeadAliasController aliasHeaderController;
    private ApisSelectBoxItemAliasController aliasItemController;
    private ApisSelectBoxHeadAddressController addressHeaderController;
    private ApisSelectBoxItemAddressController addressItemController;
    private ApisSelectBoxHeadDomainController domainHeaderController;
    private ApisSelectBoxItemDomainController domainItemController;
    private ApisSelectBoxHeadOnlyAddressController onlyAddressHeaderController;
    private ApisSelectBoxItemOnlyAddressController onlyAddressItemController;

    private ArrayList<SelectBoxWalletItemModel> walletItemModels = new ArrayList<SelectBoxWalletItemModel>();
    private ArrayList<SelectBoxDomainModel> domainItemModels = new ArrayList<SelectBoxDomainModel>();

    private SelectBoxWalletItemModel selectedItemModel = null;
    private SelectBoxDomainModel selectBoxDomainModel = null;

    @FXML
    private AnchorPane rootPane;
    @FXML
    private VBox childPane, itemList;
    @FXML
    private GridPane header;
    @FXML
    private ScrollPane scrollPane;

    @FXML
    private void onMouseClicked(javafx.scene.input.InputEvent event){
        String id = ((Node)event.getSource()).getId();
        if(id.equals("header")){
            toggleItemListVisible();

            if(handler != null){
                handler.onMouseClick();
            }
        }
        event.consume();
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        onStateDefault();
        setVisibleItemList(false);

        init(SELECT_BOX_TYPE_ALIAS);
        setStage(STAGE_DEFAULT);

        rootPane.setOnMouseExited(new EventHandler<MouseEvent>() {
            @Override
            public void handle(MouseEvent event) {
                setVisibleItemList(false);
            }
        });
    }

    public void init(int boxType){
        setSelectBoxType(boxType);
        this.walletItemModels.clear();
        this.itemList.getChildren().clear();

        // 드롭아이템 리스트 초기화
        // 내 지갑 목록이 필요한 타입 = { SELECT_BOX_TYPE_ALIAS, SELECT_BOX_TYPE_ADDRESS, SELECT_BOX_TYPE_ONLY_ADDRESS }
        // 도메인 목록이 필요한 타입 = { SELECT_BOX_TYPE_DOMAIN }
        switch (this.selectBoxType){
            case SELECT_BOX_TYPE_ALIAS :
            case SELECT_BOX_TYPE_ADDRESS :
            case SELECT_BOX_TYPE_ONLY_ADDRESS :
                for (int i = 0; i < AppManager.getInstance().getKeystoreExpList().size(); i++) {
                    KeyStoreDataExp dataExp = AppManager.getInstance().getKeystoreExpList().get(i);

                    String address = dataExp.address;
                    String alias = dataExp.alias;
                    String mask = dataExp.mask;
                    SelectBoxWalletItemModel model = new SelectBoxWalletItemModel();
                    model.addressProperty().setValue(address);
                    model.aliasProperty().setValue(alias);
                    model.maskProperty().setValue(mask);
                    model.setKeystoreId(AppManager.getInstance().getKeystoreExpList().get(i).id);
                    model.setBalance(AppManager.getInstance().getKeystoreExpList().get(i).balance);
                    model.setMineral(AppManager.getInstance().getKeystoreExpList().get(i).mineral);
                    try {
                        model.setIdenticon(IdenticonGenerator.generateIdenticonsToImage(address,128,128));
                    } catch (WriterException e) {
                        e.printStackTrace();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    addItem(this.selectBoxType, model);
                }
                if(selectedItemModel == null){
                    selectedItemModel = walletItemModels.get(0);
                }
                setHeader(this.selectBoxType, selectedItemModel);
                break;
            case SELECT_BOX_TYPE_DOMAIN :

                // 도메인 리스트 등록
                String[] domainName ={"me", "ico", "shop", "com", "org", "info", "biz", "net", "edu", "team", "pro", "xxx", "xyz", "cat", "dog", "exchange", "dapp", "firm"};
                for(int i=0; i<domainName.length ; i++){
                    addDomainItem(new SelectBoxDomainModel().setDomainId(""+i).setDomain("@"+domainName[i]).setApis("10"));
                }

                if(selectBoxDomainModel == null){
                    selectBoxDomainModel = domainItemModels.get(0);
                }
                setDomainHeader(selectBoxDomainModel);
                break;
        }

    }

    public void update(){
        AppManager.getInstance().keystoreFileReadAll();
        init(this.selectBoxType);
        if(selectedItemModel != null) {
            for(int i=0; i<walletItemModels.size(); i++){
                if(walletItemModels.get(i).getKeystoreId().equals(selectedItemModel.getKeystoreId())){
                    selectedItemModel = walletItemModels.get(i);
                    setHeader(this.selectBoxType, selectedItemModel);
                    break;
                }
            }
        }else{
            setHeader(this.selectBoxType, walletItemModels.get(0));
        }
    }

    public void onStateDefault(){
        String style = "";

        if(this.selectBoxType == SELECT_BOX_TYPE_ONLY_ADDRESS){
            style = style + "-fx-border-radius : 0 0 0 0; -fx-background-radius: 0 0 0 0; ";
            style = style + "-fx-border-color: transparent; ";
            style = style + "-fx-background-color: transparent; ";
        }else{
            style = style + "-fx-border-radius : 4 4 4 4; -fx-background-radius: 4 4 4 4; ";
            style = style + "-fx-border-color: #d8d8d8; ";
            style = style + "-fx-background-color: #f2f2f2; ";
        }
        header.setStyle(style);
    }

    public void onStateActive(){
        String style = "";

        if(this.selectBoxType == SELECT_BOX_TYPE_ONLY_ADDRESS){
            style = style + "-fx-border-radius : 0 0 0 0; -fx-background-radius: 0 0 0 0; ";
            style = style + "-fx-border-color: transparent; ";
            style = style + "-fx-background-color: transparent; ";
        }else{
            style = style + "-fx-border-radius : 4 4 4 4; -fx-background-radius: 4 4 4 4; ";
            style = style + "-fx-border-color: #999999; ";
            style = style + "-fx-background-color: #ffffff; ";
        }

        header.setStyle(style);
    }

    public void setSelectBoxType(int boxType){
        this.selectBoxType = boxType;
        if(this.selectBoxType == SELECT_BOX_TYPE_ALIAS){
            this.scrollPane.maxHeightProperty().setValue(170);
        }else if(this.selectBoxType == SELECT_BOX_TYPE_ADDRESS){
            this.scrollPane.maxHeightProperty().setValue(162);
        }else if(this.selectBoxType == SELECT_BOX_TYPE_DOMAIN){
            this.scrollPane.maxHeightProperty().setValue(162);
        }
    }
    public void setHeader(int boxType, SelectBoxWalletItemModel model){
        try {
            URL aliasHeaderUrl = getClass().getClassLoader().getResource("scene/apis_selectbox_head_alias.fxml");
            URL addressHeaderUrl = getClass().getClassLoader().getResource("scene/apis_selectbox_head_address.fxml");
            URL onlyAddressHeaderUrl = getClass().getClassLoader().getResource("scene/apis_selectbox_head_only_address.fxml");

            header.getChildren().clear();
            if(boxType == SELECT_BOX_TYPE_ALIAS){
                if(aliasHeaderController == null) {
                    FXMLLoader loader = new FXMLLoader(aliasHeaderUrl);
                    aliasHeaderNode = loader.load();
                    aliasHeaderController = (ApisSelectBoxHeadAliasController) loader.getController();
                }
                header.add(aliasHeaderNode,0,0);
                aliasHeaderController.setModel(model);

            }else if(boxType == SELECT_BOX_TYPE_ADDRESS){
                if(addressHeaderController == null) {
                    FXMLLoader loader = new FXMLLoader(addressHeaderUrl);
                    addressHeaderNode = loader.load();
                    addressHeaderController = (ApisSelectBoxHeadAddressController) loader.getController();
                }
                header.add(addressHeaderNode,0,0);
                addressHeaderController.setModel(model);
            }else if(boxType == SELECT_BOX_TYPE_ONLY_ADDRESS){
                if(onlyAddressHeaderController == null) {
                    FXMLLoader loader = new FXMLLoader(onlyAddressHeaderUrl);
                    onlyAddressHeaderNode = loader.load();
                    onlyAddressHeaderController = (ApisSelectBoxHeadOnlyAddressController) loader.getController();
                }
                header.add(onlyAddressHeaderNode,0,0);
                onlyAddressHeaderController.setModel(model);
            }

        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (IOException e){
            e.printStackTrace();
        }
    }
    public void addItem(int boxType, SelectBoxWalletItemModel model){
        try {
            URL aliasItemUrl = getClass().getClassLoader().getResource("scene/apis_selectbox_item_alias.fxml");
            URL addressItemUrl = getClass().getClassLoader().getResource("scene/apis_selectbox_item_address.fxml");
            URL onlyAddressItemUrl = getClass().getClassLoader().getResource("scene/apis_selectbox_item_only_address.fxml");
            Node itemNode = null;

            if(boxType == SELECT_BOX_TYPE_ALIAS){
                FXMLLoader loader = new FXMLLoader(aliasItemUrl);
                itemNode = loader.load();
                aliasItemController = (ApisSelectBoxItemAliasController)loader.getController();
                aliasItemController.setModel(model);
                aliasItemController.setHandler(new ApisSelectBoxItemAliasController.SelectBoxItemAliasInterface() {
                    @Override
                    public void onMouseClicked(SelectBoxWalletItemModel itemModel) {
                        selectedItemModel = itemModel;

                        ApisSelectBoxController.this.setVisibleItemList(false);
                        aliasHeaderController.setModel(itemModel);
                        setStage(STAGE_SELECTED);

                        if(handler != null){
                            handler.onSelectItem();
                        }
                    }
                });

            }else if(boxType == SELECT_BOX_TYPE_ADDRESS){
                FXMLLoader loader = new FXMLLoader(addressItemUrl);
                itemNode = loader.load();
                addressItemController = (ApisSelectBoxItemAddressController)loader.getController();
                addressItemController.setModel(model);
                addressItemController.setHandler(new ApisSelectBoxItemAddressController.SelectBoxItemAddressInterface() {
                    @Override
                    public void onMouseClicked(SelectBoxWalletItemModel itemModel) {
                        selectedItemModel = itemModel;

                        ApisSelectBoxController.this.setVisibleItemList(false);
                        addressHeaderController.setModel(itemModel);
                        setStage(STAGE_SELECTED);

                        if(handler != null){
                            handler.onSelectItem();
                        }
                    }
                });
            }else if(boxType == SELECT_BOX_TYPE_ONLY_ADDRESS){
                FXMLLoader loader = new FXMLLoader(onlyAddressItemUrl);
                itemNode = loader.load();
                onlyAddressItemController = (ApisSelectBoxItemOnlyAddressController)loader.getController();
                onlyAddressItemController.setModel(model);
                onlyAddressItemController.setHandler(new ApisSelectBoxItemOnlyAddressController.SelectBoxItemOnlyAddressInterface() {
                    @Override
                    public void onMouseClicked(SelectBoxWalletItemModel itemModel) {
                        selectedItemModel = itemModel;

                        ApisSelectBoxController.this.setVisibleItemList(false);
                        onlyAddressHeaderController.setModel(itemModel);
                        setStage(STAGE_SELECTED);

                        if(handler != null){
                            handler.onSelectItem();
                        }
                    }
                });
            }

            itemList.getChildren().add(itemNode);
            walletItemModels.add(model);

        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (IOException e){
            e.printStackTrace();
        }
    }

    private void setDomainHeader(SelectBoxDomainModel model) {
        try {
            URL aliasHeaderUrl = getClass().getClassLoader().getResource("scene/apis_selectbox_head_domain.fxml");

            header.getChildren().clear();
            if(domainHeaderController == null) {
                FXMLLoader loader = new FXMLLoader(aliasHeaderUrl);
                domainHeaderNode = loader.load();
                domainHeaderController = (ApisSelectBoxHeadDomainController) loader.getController();
            }
            domainHeaderController.setModel(model);
            header.add(domainHeaderNode,0,0);

        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (IOException e){
            e.printStackTrace();
        }
    }
    private void addDomainItem(SelectBoxDomainModel model) {
        try {
            URL aliasItemUrl = getClass().getClassLoader().getResource("scene/apis_selectbox_item_domain.fxml");
            Node itemNode = null;

            FXMLLoader loader = new FXMLLoader(aliasItemUrl);
            itemNode = loader.load();
            domainItemController = (ApisSelectBoxItemDomainController)loader.getController();
            domainItemController.setModel(model);
            domainItemController.setHandler(new ApisSelectBoxItemDomainController.SelectBoxItemDomainInterface() {
                @Override
                public void onMouseClicked(SelectBoxDomainModel itemModel) {

                    ApisSelectBoxController.this.setVisibleItemList(false);
                    domainHeaderController.setModel(itemModel);
                    setStage(STAGE_SELECTED);

                    if(handler != null){
                        handler.onSelectItem();
                    }
                }
            });

            itemList.getChildren().add(itemNode);
            domainItemModels.add(model);

        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (IOException e){
            e.printStackTrace();
        }
    }

    public void setVisibleItemList(boolean isVisible){

        if(isVisible == true){
            this.rootPane.prefHeightProperty().setValue(-1);
            setStage(STAGE_DEFAULT);

        }else{
            if(this.selectBoxType == SELECT_BOX_TYPE_ALIAS){
                this.rootPane.prefHeightProperty().setValue(56);
            }else if(this.selectBoxType == SELECT_BOX_TYPE_ADDRESS){
                this.rootPane.prefHeightProperty().setValue(40);
            }else if(this.selectBoxType == SELECT_BOX_TYPE_DOMAIN){
                this.rootPane.prefHeightProperty().setValue(40);
            }else if(this.selectBoxType == SELECT_BOX_TYPE_ONLY_ADDRESS){
                this.rootPane.prefHeightProperty().setValue(30);
            }
        }

        scrollPane.setVisible(isVisible);
    }

    public void setStage(int stage){
        if(stage == STAGE_SELECTED){
            if(selectBoxType == SELECT_BOX_TYPE_ONLY_ADDRESS){
                String style = "-fx-background-color:transparent; -fx-border-color:transparent; ";
                style = style + "-fx-border-radius : 0 0 0 0; -fx-background-radius: 0 0 0 0; ";
                header.setStyle(style);
            }else{
                String style = "-fx-background-color:#ffffff; -fx-border-color:#999999; ";
                style = style + "-fx-border-radius : 4 4 4 4; -fx-background-radius: 4 4 4 4; ";
                header.setStyle(style);
            }
        }else{
            if(selectBoxType == SELECT_BOX_TYPE_ONLY_ADDRESS){
                String style = "-fx-background-color:transparent; -fx-border-color:transparent; ";
                style = style + "-fx-border-radius : 0 0 0 0; -fx-background-radius: 0 0 0 0; ";
                header.setStyle(style);
            }else{
                String style = "-fx-background-color:#f2f2f2; -fx-border-color:#d8d8d8; ";
                style = style + "-fx-border-radius : 4 4 4 4; -fx-background-radius: 4 4 4 4; ";
                header.setStyle(style);
            }
        }
    }

    public void selectedItemWithWalletId(String id) {
        for(int i=0; i<walletItemModels.size(); i++){
            if(walletItemModels.get(i).getKeystoreId().equals(id)){
                selectedItem(i);
                break;
            }
        }
    }

    public void selectedItemWithAddress(String address){
        for(int i=0; i<walletItemModels.size(); i++){
            if(walletItemModels.get(i).getAddress().equals(address)){
                selectedItem(i);
                break;
            }
        }
    }

    public void selectedItem(int i) {
        selectedItemModel = walletItemModels.get(i);

        if(this.selectBoxType == SELECT_BOX_TYPE_ALIAS){
            aliasHeaderController.setModel(selectedItemModel);

        }else if(this.selectBoxType == SELECT_BOX_TYPE_ADDRESS){
            addressHeaderController.setModel(selectedItemModel);
        }
    }
    public void toggleItemListVisible(){
        setVisibleItemList(!scrollPane.isVisible()); }

    public String getAddress(){
        switch (this.selectBoxType){
            case SELECT_BOX_TYPE_ALIAS : return this.aliasHeaderController.getAddress().trim();
            case SELECT_BOX_TYPE_ADDRESS : return this.addressHeaderController.getAddress().trim();
            case SELECT_BOX_TYPE_ONLY_ADDRESS : return this.onlyAddressHeaderController.getAddress().trim();
        }
        return null;
    }

    public String getAlias(){
        switch (this.selectBoxType) {
            case SELECT_BOX_TYPE_ALIAS: return this.aliasHeaderController.getAlias().trim();
        }
        return null;
    }

    public String getKeystoreId() {
        switch (this.selectBoxType){
            case SELECT_BOX_TYPE_ALIAS : return this.aliasHeaderController.getKeystoreId().trim();
            case SELECT_BOX_TYPE_ADDRESS : return this.addressHeaderController.getKeystoreId().trim();
            case SELECT_BOX_TYPE_ONLY_ADDRESS : return this.onlyAddressHeaderController.getKeystoreId().trim();
        }
        return null;
    }

    public String getBalance() {
        switch (this.selectBoxType){
            case SELECT_BOX_TYPE_ALIAS : return  this.aliasHeaderController.getBalance().trim();
            case SELECT_BOX_TYPE_ADDRESS : return  this.addressHeaderController.getBalance().trim();
        }
        return null;
    }

    public BigInteger  getBalanceToBigIntiger(){
        return new BigInteger(getBalance().toString().replaceAll("[,\\.]", ""));
    }

    public String getMineral() {
        switch (this.selectBoxType){
            case SELECT_BOX_TYPE_ALIAS : return  this.aliasHeaderController.getMineral().trim();
            case SELECT_BOX_TYPE_ADDRESS : return  this.addressHeaderController.getMineral().trim();
            case SELECT_BOX_TYPE_ONLY_ADDRESS : return this.onlyAddressHeaderController.getMineral().trim();
        }
        return null;
    }

    public ApisSelectBoxImpl getHandler() { return handler; }

    public void setHandler(ApisSelectBoxImpl handler) { this.handler = handler; }

    public String getDomain() {
        switch (this.selectBoxType){
            case SELECT_BOX_TYPE_DOMAIN : return  this.domainHeaderController.getDomain().trim();
        }
        return null;
    }

    public String getValueApis(){
        switch (this.selectBoxType){
            case SELECT_BOX_TYPE_DOMAIN : return  this.domainHeaderController.getApis().trim();
        }
        return null;
    }
    public BigInteger getValueApisToBigInt() {
        switch (this.selectBoxType){
            case SELECT_BOX_TYPE_DOMAIN : return  new BigInteger(this.domainHeaderController.getApis()).multiply(new BigInteger("1000000000000000000"));
        }
        return null;
    }
    public String getDomainId(){
        switch (this.selectBoxType){
            case SELECT_BOX_TYPE_DOMAIN : return  this.domainHeaderController.getDomainId().trim();
        }
        return null;
    }

    public interface ApisSelectBoxImpl{
        void onMouseClick();
        void onSelectItem();
    }
}
