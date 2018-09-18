package org.apis.gui.controller;

import com.google.zxing.WriterException;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.shape.Ellipse;
import javafx.scene.control.*;
import org.apis.db.sql.DBManager;
import org.apis.gui.common.IdenticonGenerator;
import org.apis.gui.common.JavaFXStyle;
import org.apis.gui.manager.AppManager;
import org.apis.gui.manager.StringManager;
import org.apis.gui.model.ContractModel;
import org.spongycastle.util.encoders.Hex;

import java.io.IOException;
import java.net.URL;
import java.util.ResourceBundle;

public class PopupContractReadWriteCreateController implements Initializable {

    @FXML
    private ImageView addrCircleImg;
    @FXML
    private GridPane contractAddressBg;
    @FXML
    private TextField contractNameTextField, contractAddressTextField;

    // Multilingual Support Labels
    @FXML
    private Label readWriteTitle, readWriteCreate, addrLabel, nameLabel, jsonInterfaceLabel, noBtn, createBtn;
    @FXML
    private TextArea abiTextarea;
    private Image greyCircleAddrImg = new Image("image/ic_circle_grey@2x.png");

    public void exit(){ AppManager.getInstance().guiFx.hideMainPopup(1); }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        init();
    }

    public void init() {
        // Multilingual Support
        languageSetting();

        Ellipse ellipse = new Ellipse(12, 12);
        ellipse.setCenterX(12);
        ellipse.setCenterY(12);

        addrCircleImg.setClip(ellipse);

        contractAddressTextField.focusedProperty().addListener(new ChangeListener<Boolean>() {
            @Override
            public void changed(ObservableValue<? extends Boolean> observable, Boolean oldValue, Boolean newValue) {
                if(newValue){
                    contractAddressBg.setStyle(new JavaFXStyle(contractAddressBg.getStyle()).add("-fx-background-color", "#ffffff").toString());
                    contractAddressTextField.setStyle(new JavaFXStyle(contractAddressTextField.getStyle()).add("-fx-background-color", "#ffffff").toString());
                }else{
                    contractAddressBg.setStyle(new JavaFXStyle(contractAddressBg.getStyle()).add("-fx-background-color", "#f2f2f2").toString());
                    contractAddressTextField.setStyle(new JavaFXStyle(contractAddressTextField.getStyle()).add("-fx-background-color", "#f2f2f2").toString());
                }
            }
        });

        contractAddressTextField.textProperty().addListener(new ChangeListener<String>() {
            @Override
            public void changed(ObservableValue<? extends String> observable, String oldValue, String newValue) {
                int maxlangth = 40;
                if(contractAddressTextField.getText().length() > maxlangth){
                    contractAddressTextField.setText(contractAddressTextField.getText().substring(0, maxlangth));
                }

                if(newValue.length() >= maxlangth){
                    try {
                        Image image = IdenticonGenerator.generateIdenticonsToImage(newValue, 128, 128);
                        if(image != null){
                            addrCircleImg.setImage(image);
                            image = null;
                        }
                    } catch (WriterException e) {
                        e.printStackTrace();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }else{
                    addrCircleImg.setImage(greyCircleAddrImg);
                }

            }
        });
    }

    public void languageSetting() {
        readWriteTitle.textProperty().bind(StringManager.getInstance().contractPopup.readWriteTitle);
        readWriteCreate.textProperty().bind(StringManager.getInstance().contractPopup.readWriteCreate);
        addrLabel.textProperty().bind(StringManager.getInstance().contractPopup.addrLabel);
        nameLabel.textProperty().bind(StringManager.getInstance().contractPopup.nameLabel);
        contractNameTextField.promptTextProperty().bind(StringManager.getInstance().contractPopup.namePlaceholder);
        jsonInterfaceLabel.textProperty().bind(StringManager.getInstance().contractPopup.jsonInterfaceLabel);
        noBtn.textProperty().bind(StringManager.getInstance().contractPopup.noBtn);
        createBtn.textProperty().bind(StringManager.getInstance().contractPopup.createBtn);
    }

    public void createBtnClicked() {
        String address = contractAddressTextField.getText();
        String name = contractNameTextField.getText();
        String abi = this.abiTextarea.getText();

        DBManager.getInstance().updateContract(Hex.decode(address), name,null, abi, null);
        AppManager.getInstance().guiFx.hideMainPopup(1);
        AppManager.getInstance().guiFx.showMainPopup("popup_contract_read_write_select.fxml", 0);
    }
}
