package org.apis.gui.controller.module.ledger;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.AnchorPane;
import org.apis.gui.manager.AppManager;
import org.apis.gui.manager.ImageManager;
import org.apis.gui.manager.InputConditionManager;

import java.net.URL;
import java.util.ArrayList;
import java.util.ResourceBundle;

public class DerivationPathTextItemController extends DerivationPathItemController {
    @FXML private AnchorPane bgAnchor;
    @FXML private ImageView checkImg;
    @FXML private Label categoryLabel;
    @FXML private TextField derivationTextField1, derivationTextField2, derivationTextField3, derivationTextField4;

    private ArrayList<TextField> textFields = new ArrayList<TextField>();
    private DerivationPathItemImpl handler;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        textFields.add(derivationTextField1);
        textFields.add(derivationTextField2);
        textFields.add(derivationTextField3);
        textFields.add(derivationTextField4);

        // Focus listener
        derivationTextField1.focusedProperty().addListener(focusListener);
        derivationTextField2.focusedProperty().addListener(focusListener);
        derivationTextField3.focusedProperty().addListener(focusListener);
        derivationTextField4.focusedProperty().addListener(focusListener);

        // Text Listener
        derivationTextField1.textProperty().addListener(InputConditionManager.onlyIntegerListener());
        derivationTextField2.textProperty().addListener(InputConditionManager.onlyIntegerListener());
        derivationTextField3.textProperty().addListener(InputConditionManager.onlyIntegerListener());
        derivationTextField4.textProperty().addListener(InputConditionManager.onlyIntegerListener());

        derivationTextField1.textProperty().addListener(textListener);
        derivationTextField2.textProperty().addListener(textListener);
        derivationTextField3.textProperty().addListener(textListener);
        derivationTextField4.textProperty().addListener(textListener);

        // Set textField style
        AppManager.settingTextFieldStyle(derivationTextField1);
        AppManager.settingTextFieldStyle(derivationTextField2);
        AppManager.settingTextFieldStyle(derivationTextField3);
        AppManager.settingTextFieldStyle(derivationTextField4);

        bgAnchor.setOnMouseClicked(new EventHandler<MouseEvent>() {
            @Override
            public void handle(MouseEvent event) {
                if(handler != null) {
                    handler.clicked();
                }
            }
        });
    }

    public void init(SimpleStringProperty category) {
        this.categoryLabel.textProperty().bind(category);
    }

    private ChangeListener<Boolean> focusListener = new ChangeListener<Boolean>() {
        @Override
        public void changed(ObservableValue<? extends Boolean> observable, Boolean oldValue, Boolean newValue) {
            if(newValue) {
                if (handler != null) {
                    handler.clicked();
                }
            }
        }
    };

    private ChangeListener<String> textListener = new ChangeListener<String>() {
        @Override
        public void changed(ObservableValue<? extends String> observable, String oldValue, String newValue) {
            StringProperty string = (StringProperty) observable;
            if(newValue.length() > 4) {
                string.set(newValue.substring(0, 4));
            }
        }
    };

    @Override
    public void check() {
        checked = true;
        this.checkImg.setImage(ImageManager.checkRed);
    }

    @Override
    public void unCheck() {
        checked = false;
        this.checkImg.setImage(ImageManager.checkGrey);
    }

    public void setHandler(DerivationPathItemImpl handler) {
        this.handler = handler;
    }
}
