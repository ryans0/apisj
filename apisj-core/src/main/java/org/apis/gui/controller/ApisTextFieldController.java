package org.apis.gui.controller;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.image.Image;
import javafx.scene.input.InputEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Pane;
import jdk.internal.util.xml.impl.Input;

import java.lang.reflect.Array;
import java.net.URL;
import java.util.Arrays;
import java.util.ResourceBundle;
import java.util.regex.Pattern;

public class ApisTextFieldController implements Initializable {
    public static final int TEXTFIELD_TYPE_TEXT = 0;
    public static final int TEXTFIELD_TYPE_PASS = 1;
    public static final int CHECKBTN_TYPE_NONE = 0;
    public static final int CHECKBTN_TYPE_PROGRESS = 1;
    public static final int CHECKBTN_TYPE_FAIL = 2;
    public static final int CHECKBTN_TYPE_SUCCESS = 3;
    public static final boolean CHECKBTN_EXITED = false;
    public static final boolean CHECKBTN_ENTERED = true;

    private int textFieldType = TEXTFIELD_TYPE_TEXT;
    private int checkBtnType = CHECKBTN_TYPE_NONE;
    private boolean checkBtnEnteredFlag = CHECKBTN_EXITED;
    private boolean[] pwValidationFlag = new boolean[3];
    private Pattern pwPatternLetters = Pattern.compile("[a-zA-Zㄱ-ㅎㅏ-ㅣ가-힣]");
    private Pattern pwPatternNumbers = Pattern.compile("[0-9]");
    private Pattern pwPatternSpecials = Pattern.compile("[^a-zA-Zㄱ-ㅎㅏ-ㅣ가-힣0-9]");
    private Pattern pkPatternValidation = Pattern.compile("[^0-9a-fA-F]");

    private String style = "-fx-background-insets: 0, 0 0 0 0; -fx-background-color: transparent; -fx-prompt-text-fill: #999999; " +
            "-fx-font-family: 'Open Sans SemiBold'; -fx-font-size: 12px;";

    private ApisTextFieldControllerInterface handler;

    private Node removeNode;

    @FXML
    private TextField textField;
    @FXML
    private PasswordField passwordField;
    @FXML
    private ImageView coverBtn, checkBtn, messageImg;
    @FXML
    private GridPane message, textFieldGrid;
    @FXML
    private Pane borderLine;
    @FXML
    private Label messageLabel;

    private Image circleCrossGreyCheckBtn, circleCrossRedCheckBtn, greenCheckBtn, errorRed, passwordPublic, passwordPrivate;

    @Override
    public void initialize(URL location, ResourceBundle resources) {

        circleCrossGreyCheckBtn = new Image("image/ic_circle_cross_grey@2x.png");
        circleCrossRedCheckBtn = new Image("image/ic_circle_cross_red@2x.png");
        errorRed = new Image("image/ic_error_red@2x.png");
        greenCheckBtn = new Image("image/ic_check@2x.png");
        passwordPublic = new Image("image/ic_public@2x.png");
        passwordPrivate = new Image("image/ic_private@2x.png");

        textField.focusedProperty().addListener(textFieldListener);
        passwordField.focusedProperty().addListener(textFieldListener);

        textField.textProperty().bindBidirectional(passwordField.textProperty());
        textField.textProperty().addListener(new ChangeListener<String>() {
            @Override
            public void changed(ObservableValue<? extends String> observable, String oldValue, String newValue) {
                if(handler != null){
                    handler.change(oldValue, newValue);
                }
            }
        });

        init(TEXTFIELD_TYPE_PASS, "");
        Arrays.fill(pwValidationFlag, Boolean.FALSE);
    }

    private ChangeListener<Boolean> textFieldListener = new ChangeListener<Boolean>() {
        @Override
        public void changed(ObservableValue<? extends Boolean> observable, Boolean oldValue, Boolean newValue) {
            if(newValue) {
                onFocusIn();
            } else {
                onFocusOut();
            }
        }
    };

    @FXML
    private void onMouseClicked(InputEvent event){
        String fxid = ((Node)event.getSource()).getId();

        if(fxid.equals("coverBtn")){
            togglePasswordField();

            if(this.passwordField.isVisible()) {
                this.coverBtn.setImage(passwordPrivate);
            } else {
                this.coverBtn.setImage(passwordPublic);
            }

        }else if(fxid.equals("checkBtn")){

            if(this.checkBtnEnteredFlag == CHECKBTN_ENTERED){
                if(this.checkBtnType == CHECKBTN_TYPE_PROGRESS
                    || this.checkBtnType == CHECKBTN_TYPE_FAIL) {
                        this.textField.textProperty().setValue("");
                        this.passwordField.textProperty().setValue("");
                }
            }
        }
    }

    @FXML
    private void onMouseEntered(InputEvent event) {
        String fxid = ((Node)event.getSource()).getId();

        if(fxid.equals("checkBtn")){
            this.checkBtnEnteredFlag = CHECKBTN_ENTERED;
        }
    }

    @FXML
    private void onMouseExited(InputEvent event) {
        String fxid = ((Node)event.getSource()).getId();

        if(fxid.equals("checkBtn")){
            this.checkBtnEnteredFlag = CHECKBTN_EXITED;
        }
    }

    private void onFocusIn() {
        this.checkBtnType = CHECKBTN_TYPE_PROGRESS;

        this.borderLine.setStyle("-fx-background-color: #36b25b;");
        this.textField.setStyle(style+" -fx-text-fill: #2b2b2b;");
        this.passwordField.setStyle(style+" -fx-text-fill: #2b2b2b;");
        this.checkBtn.setImage(circleCrossGreyCheckBtn);
        this.checkBtn.setCursor(Cursor.HAND);
        this.checkBtn.setVisible(true);
    }

    private void onFocusOut(){
        if(handler != null){
            handler.onFocusOut();
        }
    }

    public void failedForm(String text){
        this.checkBtnType = CHECKBTN_TYPE_FAIL;

        this.borderLine.setStyle("-fx-background-color: #910000;");
        this.checkBtn.setImage(circleCrossRedCheckBtn);
        this.checkBtn.setCursor(Cursor.HAND);
        this.messageLabel.setText(text);
        this.message.setVisible(true);
    }

    public void succeededForm() {
        this.checkBtnType = CHECKBTN_TYPE_SUCCESS;

        this.borderLine.setStyle("-fx-background-color: #2b2b2b;");
        this.checkBtn.setImage(greenCheckBtn);
        this.checkBtn.setCursor(Cursor.DEFAULT);
        this.message.setVisible(false);
    }

    public boolean pwValidate(String password) {
        boolean result = false;
        int sum = 0;
        Arrays.fill(pwValidationFlag, Boolean.FALSE);

        if(pwPatternLetters.matcher(password).find()) {
            pwValidationFlag[0] = true;
        }
        if(pwPatternNumbers.matcher(password).find()) {
            pwValidationFlag[1] = true;
        }
        if(pwPatternSpecials.matcher(password).find()) {
            pwValidationFlag[2] = true;
        }

        for(int i=0; i<pwValidationFlag.length; i++) {
            if(pwValidationFlag[i] == true)
                sum++;
        }

        if(sum == 3) {
            result = true;
        }

        return result;
    }

    public boolean pkValidate(String privateKey) {
        boolean result = false;

        if(pkPatternValidation.matcher(privateKey).find()) {
            result = true;
        }

        return result;
    }

    public void togglePasswordField(){
        if(textField.isVisible()){
            passwordField.setVisible(true);
            textField.setVisible(false);
            passwordField.requestFocus();
        } else {
            textField.setVisible(true);
            passwordField.setVisible(false);
            textField.requestFocus();
        }
    }

    public void init(int type, String placeHolder){
        this.textFieldType = type;
        this.checkBtnType = CHECKBTN_TYPE_NONE;

        this.checkBtn.setVisible(false);
        this.borderLine.setStyle("-fx-background-color: #2b2b2b");
        this.message.setVisible(false);
        this.textField.setStyle(style+" -fx-text-fill: #2b2b2b;");
        this.passwordField.setStyle(style+" -fx-text-fill: #2b2b2b;");
        this.textField.setPromptText(placeHolder);
        this.passwordField.setPromptText(placeHolder);

        if(textFieldType == TEXTFIELD_TYPE_TEXT){
            this.textField.textProperty().setValue("");
            this.passwordField.setVisible(false);
            this.textField.setVisible(true);
            this.textField.setPadding(new Insets(0, 8, 0, 2));
            if(removeNode == null){
                removeNode = this.textFieldGrid.getChildren().remove(2);
            }

        }else if(textFieldType == TEXTFIELD_TYPE_PASS){
            this.passwordField.textProperty().setValue("");
            this.textField.setVisible(false);
            this.passwordField.setVisible(true);
            this.coverBtn.setImage(passwordPrivate);
        }
    }

    public void setText(String text) { this.textField.textProperty().setValue(text); }
    public void setHandler(ApisTextFieldControllerInterface handler){ this.handler = handler; }

    public boolean getCheckBtnEnteredFlag() { return this.checkBtnEnteredFlag; }
    public String getText(){ return this.textField.getText().trim();}
    public int getCheckBtnType() { return this.checkBtnType; }
    public ApisTextFieldControllerInterface getHandler() { return this.handler; }



    public interface ApisTextFieldControllerInterface {
        void onFocusOut();
        void change(String old_text, String new_text);
    }
}
