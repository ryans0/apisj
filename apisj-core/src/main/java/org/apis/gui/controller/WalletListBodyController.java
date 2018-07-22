package org.apis.gui.controller;

import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.InputEvent;
import javafx.scene.layout.AnchorPane;
import javafx.scene.layout.GridPane;
import javafx.scene.shape.Rectangle;
import org.apis.gui.manager.AppManager;
import org.apis.gui.model.WalletItemModel;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.net.URL;
import java.util.ResourceBundle;

public class WalletListBodyController implements Initializable {

    public static final int WALLET_LIST_BODY_TYPE_APIS = 0;
    public static final int WALLET_LIST_BODY_TYPE_APIS_ADDRESS = 1;
    public static final int WALLET_LIST_BODY_TYPE_MINERAL = 2;
    public static final int WALLET_LIST_BODY_TYPE_MINERAL_ADDRESS = 3;
    private int bodyType = WALLET_LIST_BODY_TYPE_APIS;

    private static final int BODY_COPY_STATE_NONE = 0;
    private static final int BODY_COPY_STATE_NORMAL = 1;
    private static final int BODY_COPY_STATE_ACTIVE = 2;

    private WalletItemModel model;
    private Image apisIcon, mineraIcon;

    private WalletListBodyInterface handler;

    @FXML
    private AnchorPane rootPane;
    @FXML
    private GridPane unitTypePane, groupTypePane;

    // unit type element
    @FXML
    private ImageView icon;
    @FXML
    private Label name, valueUnit, valueNatural, valueDecimal;

    // group type element
    @FXML
    private Label valueUnit1, labelWalletAlias, labelWalletAddress, btnCopy, valueNatural1, valueDecimal1;
    @FXML
    private AnchorPane miningPane;
    @FXML
    private ImageView icon1;


    @FXML
    public void onMouseClicked(InputEvent event){
        String id = ((Node)event.getSource()).getId();
        if(id.equals("rootPane")){
        }else if(id.equals("btnCheckBox")){
        }else if(id.equals("btnCopy")){
            System.out.println("alias[1] : "+model.getAlias());

            String text = labelWalletAddress.getText();
            StringSelection stringSelection = new StringSelection(text);
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(stringSelection, null);
            if(this.handler != null){
                this.handler.onClickCopy(text);
            }

            setCopyState(BODY_COPY_STATE_NONE);

        }else if(id.equals("btnAddressMasking")){
            if(this.handler != null){
                this.handler.onClickAddressMasking(event);
            }
        }else if(id.equals("btnTransfer")){
            if(this.handler != null){
                this.handler.onClickTransfer(event);
            }
        }
    }
    @FXML
    public void onMouseEntered(InputEvent event){
        String id = ((Node)event.getSource()).getId();
        if(id.equals("paneAddress")){
            setCopyState(BODY_COPY_STATE_NORMAL);
        }else if(id.equals("btnCopy")){
            setCopyState(BODY_COPY_STATE_ACTIVE);
        }
    }
    @FXML
    public void onMouseExited(InputEvent event){
        String id = ((Node)event.getSource()).getId();
        if(id.equals("paneAddress")){
            setCopyState(BODY_COPY_STATE_NONE);
        }else if(id.equals("btnCopy")){
            setCopyState(BODY_COPY_STATE_NORMAL);
        }
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        apisIcon = new Image("image/ic_apis@2x.png");
        mineraIcon = new Image("image/ic_mineral@2x.png");

        // set a clip to apply rounded border to the original image.
        Rectangle clip = new Rectangle( icon1.getFitWidth(), icon1.getFitHeight() );
        clip.setArcWidth(30);
        clip.setArcHeight(30);
        icon1.setClip(clip);

        init(WALLET_LIST_BODY_TYPE_APIS);
    }


    public WalletListBodyController init(int type){
        this.bodyType = type;

        switch (this.bodyType){
            case WALLET_LIST_BODY_TYPE_APIS : case WALLET_LIST_BODY_TYPE_MINERAL :
                unitTypePane.setVisible(true);
                groupTypePane.setVisible(false);
                break;

            case WALLET_LIST_BODY_TYPE_APIS_ADDRESS : case WALLET_LIST_BODY_TYPE_MINERAL_ADDRESS :
                unitTypePane.setVisible(false);
                groupTypePane.setVisible(true);

                break;
        }
        return this;
    }

    public void setBalance(String balance){
        if(balance == null) return;

        String newBalance = AppManager.addDotWidthIndex(balance);
        String[] splitBalance = newBalance.split("\\.");

        switch (this.bodyType){
            case WALLET_LIST_BODY_TYPE_APIS :
                this.model.apisNaturalProperty().setValue(splitBalance[0]);
                this.model.apisDecimalProperty().setValue("."+splitBalance[1]);
                break;

            case WALLET_LIST_BODY_TYPE_MINERAL :
                this.model.mineralNaturalProperty().setValue(splitBalance[0]);
                this.model.mineralDecimalProperty().setValue("."+splitBalance[1]);
                break;
        }
    }
    public String getBalance(){
        String result = "";
        switch (this.bodyType){
            case WALLET_LIST_BODY_TYPE_APIS :
                result = result + this.model.getApisNatural();
                result = result + this.model.getApisDecimal();
                break;

            case WALLET_LIST_BODY_TYPE_MINERAL :
                result = result + this.model.getMineralNatural();
                result = result + this.model.getMineralDecimal();
                break;
        }

        return result.replace(".","");
    }


    public void show(){
        this.rootPane.setMinHeight(52.0);
        this.rootPane.setMaxHeight(52.0);
        this.rootPane.setPrefHeight(52.0);
        this.rootPane.setVisible(true);
    }
    public void hide(){
        this.rootPane.setMinHeight(0.0);
        this.rootPane.setMaxHeight(0.0);
        this.rootPane.setPrefHeight(0.0);
        this.rootPane.setVisible(false);
    }

    public void setModel(WalletItemModel model){
        this.model = model;

        valueNatural.textProperty().unbind();
        valueDecimal.textProperty().unbind();
        switch (this.bodyType){
            case WALLET_LIST_BODY_TYPE_APIS :
                name.setText(WalletItemModel.WALLET_NAME_APIS);
                valueUnit.setText(WalletItemModel.UNIT_TYPE_STRING_APIS);
                icon.setImage(apisIcon);
                valueNatural.textProperty().bind(this.model.apisNaturalProperty());
                valueDecimal.textProperty().bind(this.model.apisDecimalProperty());
                break;
            case WALLET_LIST_BODY_TYPE_MINERAL :
                name.setText(WalletItemModel.WALLET_NAME_MINERAL);
                valueUnit.setText(WalletItemModel.UNIT_TYPE_STRING_MINERAL);
                icon.setImage(mineraIcon);
                valueNatural.textProperty().bind(this.model.mineralNaturalProperty());
                valueDecimal.textProperty().bind(this.model.mineralDecimalProperty());
                break;
            case WALLET_LIST_BODY_TYPE_APIS_ADDRESS :
                valueUnit1.setText(WalletItemModel.UNIT_TYPE_STRING_APIS);
                labelWalletAlias.textProperty().bind(this.model.aliasProperty());
                labelWalletAddress.textProperty().bind(this.model.addressProperty());
                valueNatural1.textProperty().bind(this.model.apisNaturalProperty());
                valueDecimal1.textProperty().bind(this.model.apisDecimalProperty());
                miningPane.visibleProperty().bind(this.model.miningProperty());
                break;
            case WALLET_LIST_BODY_TYPE_MINERAL_ADDRESS :
                valueUnit1.setText(WalletItemModel.UNIT_TYPE_STRING_MINERAL);
                labelWalletAlias.textProperty().bind(this.model.aliasProperty());
                labelWalletAddress.textProperty().bind(this.model.addressProperty());
                valueNatural1.textProperty().bind(this.model.mineralNaturalProperty());
                valueDecimal1.textProperty().bind(this.model.mineralDecimalProperty());
                miningPane.visibleProperty().bind(this.model.miningProperty());
                break;
        }

        setCopyState(BODY_COPY_STATE_NONE);

    }
    public WalletItemModel getModel() { return this.model; }

    private void setCopyState(int state){
        switch (state){
            case BODY_COPY_STATE_NONE :
                this.btnCopy.setStyle("-fx-background-color:#999999;");
                this.btnCopy.setVisible(false);
                break;

            case BODY_COPY_STATE_NORMAL :
                this.btnCopy.setStyle("-fx-background-color:#999999;");
                this.btnCopy.setVisible(true);
                break;

            case BODY_COPY_STATE_ACTIVE :
                this.btnCopy.setStyle("-fx-background-color:#910000;");
                this.btnCopy.setVisible(true);
                break;
        }
    }

    public Node getRootPane() { return this.rootPane; }

    public interface WalletListBodyInterface{
        void onClickEvent(InputEvent event);
        void onClickTransfer(InputEvent event);
        void onChangeCheck(WalletItemModel model, boolean isChecked);
        void onClickCopy(String address);
        void onClickAddressMasking(InputEvent event);
    }
    public void setHandler(WalletListBodyInterface handler){ this.handler = handler; }
    public WalletListBodyInterface getHandler(){ return this.handler; }
}
