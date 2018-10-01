package org.apis.gui.model;

import com.google.zxing.WriterException;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.image.Image;
import org.apis.gui.common.IdenticonGenerator;
import org.apis.gui.manager.AppManager;
import org.apis.util.blockchain.ApisUtil;

import java.io.IOException;
import java.math.BigInteger;

public class WalletItemModel {

    public static final int UNIT_TYPE_APIS = 0;
    public static final int UNIT_TYPE_MINERAL = 1;

    public static final String UNIT_TYPE_STRING_APIS = "APIS";
    public static final String UNIT_TYPE_STRING_MINERAL = "MNR";
    public static final String WALLET_NAME_APIS = "APIS";
    public static final String WALLET_NAME_MINERAL = "MINERAL";


    private SimpleStringProperty id = new SimpleStringProperty();
    private SimpleStringProperty alias = new SimpleStringProperty();
    private SimpleStringProperty address = new SimpleStringProperty();
    private SimpleStringProperty unit = new SimpleStringProperty();
    private SimpleStringProperty natural = new SimpleStringProperty();
    private SimpleStringProperty apisNatural = new SimpleStringProperty();
    private SimpleStringProperty mineralNatural = new SimpleStringProperty();
    private SimpleStringProperty totalApisNatural = new SimpleStringProperty();
    private SimpleStringProperty totalMineralNatural = new SimpleStringProperty();
    private String mask;

    private SimpleBooleanProperty mining = new SimpleBooleanProperty();
    private SimpleBooleanProperty masterNode = new SimpleBooleanProperty();

    private SimpleObjectProperty icon = new SimpleObjectProperty();

    private String keystoreJsonData;
    private Image identicon;

    public WalletItemModel(){
        setBalance("0");
        setMineral("0");
    }

    public WalletItemModel setHeaderUnitType(int unitType) {
        natural.unbind();
        switch (unitType) {
            case UNIT_TYPE_APIS:
                natural.bind(apisNatural);
                this.unit.setValue(UNIT_TYPE_STRING_APIS);
                break;
            case UNIT_TYPE_MINERAL:
                natural.bind(mineralNatural);
                this.unit.setValue(UNIT_TYPE_STRING_MINERAL);
                break;
        }
        return this;
    }
    public void setBalance(String balance){
        this.apisNatural.setValue(ApisUtil.readableApis(new BigInteger(balance),',',false));
    }
    public String getBalance(){
        return this.apisNatural.getValue();
    }
    public void setMineral(String balance){
        this.mineralNatural.setValue(ApisUtil.readableApis(new BigInteger(balance),',',false));
    }
    public String getMineral(){
        return this.mineralNatural.getValue();
    }

    public String getId() { return id.get(); }

    public SimpleStringProperty idProperty() { return id; }

    public void setId(String id) { this.id.set(id);}

    public String getAlias() {
        return alias.get();
    }

    public SimpleStringProperty aliasProperty() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias.set(alias);
    }

    public String getAddress() {
        return address.get();
    }

    public SimpleStringProperty addressProperty() {
        return address;
    }

    public void setAddress(String address) {
        this.address.set(address);
        try {
            setIdenticon(IdenticonGenerator.generateIdenticonsToImage(address, 128, 128));
        } catch (WriterException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public String getUnit() {
        return unit.get();
    }

    public SimpleStringProperty unitProperty() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit.set(unit);
    }

    public String getNatural() {
        return natural.get();
    }

    public SimpleStringProperty naturalProperty() {
        return natural;
    }

    public void setNatural(String natural) {
        this.natural.set(natural);
    }

    public String getApisNatural() {
        return apisNatural.get().replaceAll(" ","");
    }

    public SimpleStringProperty apisNaturalProperty() {
        return apisNatural;
    }

    public void setApisNatural(String apisNatural) {
        this.apisNatural.set(apisNatural);
    }

    public String getMineralNatural() {
        return mineralNatural.get().replaceAll(" ","");
    }

    public SimpleStringProperty mineralNaturalProperty() {
        return mineralNatural;
    }

    public void setMineralNatural(String mineralNatural) {
        this.mineralNatural.set(mineralNatural);
    }

    public String getTotalApisNatural() { return totalApisNatural.get(); }

    public SimpleStringProperty totalApisNaturalProperty() { return totalApisNatural; }

    public void setTotalApisNatural(String totalApisNatural) { this.totalApisNatural.set(totalApisNatural); }

    public String getTotalMineralNatural() { return totalMineralNatural.get(); }

    public SimpleStringProperty totalMineralNaturalProperty() { return totalMineralNatural; }

    public void setTotalMineralNatural(String totalMineralNatural) { this.totalMineralNatural.set(totalMineralNatural); }

    public void setKeystoreJsonData(String keystoreJsonData) { this.keystoreJsonData = keystoreJsonData; }

    public String getKstoreJsonData() { return this.keystoreJsonData; }

    public boolean isMining() { return mining.get(); }

    public SimpleBooleanProperty miningProperty() { return mining; }

    public void setMining(boolean mining) { this.mining.set(mining); }

    public boolean isMasterNode() { return masterNode.get(); }

    public SimpleBooleanProperty masterNodeProperty() { return masterNode; }

    public void setMasterNode(boolean masterNode) { this.masterNode.set(masterNode); }

    public Object getIcon() { return icon.get(); }

    public SimpleObjectProperty iconProperty() { return icon; }

    public void setIcon(Object icon) { this.icon.set(icon); }

    public String getMask() { return mask; }

    public void setMask(String mask) { this.mask = mask; }

    public Image getIdenticon() {
        return identicon;
    }

    public void setIdenticon(Image identicon) {
        this.identicon = identicon;
    }
}
