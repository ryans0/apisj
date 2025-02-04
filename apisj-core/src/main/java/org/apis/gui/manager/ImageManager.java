package org.apis.gui.manager;

import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.shape.Rectangle;
import org.apis.gui.common.IdenticonGenerator;
import org.apis.util.AddressUtil;

public class ImageManager {
    public static final Image apisIcon = new Image("image/ic_apis@2x.png");
    public static final Image mineraIcon = new Image("image/ic_mineral@2x.png");
    public static final Image hintImageCheck = new Image("image/ic_check_green@2x.png");
    public static final Image hintImageError = new Image("image/ic_error_red@2x.png");

    public static final Image tooltipReward = new Image("image/tooltip_reward@2x.png");

    public static final Image btnAddressInfo = new Image("image/btn_addressinfo@2x.png");
    public static final Image btnAddressInfoHover = new Image("image/btn_addressinfo_hover@2x.png");
    public static final Image btnSetting = new Image("image/btn_setting@2x.png");
    public static final Image btnSettingHover = new Image("image/btn_setting_hover@2x.png");
    public static final Image btnSettingUpdate = new Image("image/btn_setting_update@2x.png");
    public static final Image btnSettingUpdateHover = new Image("image/btn_setting_update_hover@2x.png");
    public static final Image icCircleHalfShow = new Image("image/ic_circle_half_show@2x.png");
    public static final Image icCircleHalfHover = new Image("image/ic_circle_half_hover@2x.png");

    public static final Image btnSearchTokenOut = new Image("image/btn_search_none@2x.png");
    public static final Image btnSearchTokenIn = new Image("image/btn_search_red@2x.png");
    public static final Image btnChangeName = new Image("image/btn_chage_walletname@2x.png");
    public static final Image btnChangeNameHover = new Image("image/btn_chage_walletname_hover@2x.png");
    public static final Image btnChangePassword  = new Image("image/btn_chage_walletpassword@2x.png");
    public static final Image btnChangePasswordHover  = new Image("image/btn_chage_walletpassword_hover@2x.png");

    public static final Image btnChangeProofKey = new Image("image/ic_tool_knowledgekey@2x.png");
    public static final Image btnChangeProofKeyHover = new Image("image/ic_tool_knowledgekey_hover@2x.png");
    public static final Image btnChangeProofKeyUsed = new Image("image/ic_tool_knowledgekey_click@2x.png");

    public static final Image btnBackupWallet  = new Image("image/btn_backupwallet@2x.png");
    public static final Image btnBackupWalletHover  = new Image("image/btn_backupwallet_hover@2x.png");
    public static final Image btnRemoveWallet = new Image("image/btn_deletewallet@2x.png");
    public static final Image btnRemoveWalletHover = new Image("image/btn_deletewallet_hover@2x.png");
    public static final Image btnMiningGrey = new Image("image/ic_miningwallet_grey@2x.png");
    public static final Image btnMiningRed = new Image("image/ic_miningwallet_red@2x.png");
    public static final Image btnMiningBlack = new Image("image/ic_miningwallet_black@2x.png");
    public static final Image btnMasternodeGrey = new Image("image/ic_masternode_grey@2x.png");
    public static final Image btnMasternodeRed = new Image("image/ic_masternode_red@2x.png");
    public static final Image btnMasternodeBlack = new Image("image/ic_masternode_black@2x.png");

    public static final Image icTransfer = new Image("image/btn_transfer_arrow@2x.png");
    public static final Image icTransferHover = new Image("image/btn_transfer_arrow_h@2x.png");
    public static final Image icAddAddressMasking = new Image("image/btn_addressmasking_plus@2x.png");
    public static final Image icAddAddressMaskingHover = new Image("image/btn_addressmasking_plus_hover@2x.png");

    public static final Image btnKeyDelete = new Image("image/btn_keydelete_none@2x.png");
    public static final Image btnKeyDeleteHover = new Image("image/btn_keydelete_hover@2x.png");

    public static final Image icSortNONE = new Image("image/ic_sort_none@2x.png");
    public static final Image icSortASC = new Image("image/ic_sort_up@2x.png");
    public static final Image icSortDESC = new Image("image/ic_sort_down@2x.png");


    public static final Image icFold = new Image("image/btn_fold@2x.png");
    public static final Image icUnFold = new Image("image/btn_unfold@2x.png");
    public static final Image icCheck = new Image("image/btn_circle_red@2x.png");
    public static final Image icCheckGrayLine = new Image("image/btn_circle_gray_line@2x.png");
    public static final Image icUnCheck = new Image("image/btn_circle_none@2x.png");

    public static final Image icCheckGreen = new Image("image/ic_check_green@2x.png");
    public static final Image icErrorRed = new Image("image/ic_error_red@2x.png");

    public static final Image bgRegisterMask = new Image("image/bg_registermask-none@2x.png");
    public static final Image bgHandOverMask = new Image("image/bg_handovermask-none@2x.png");
    public static final Image bgRegisterDomain = new Image("image/bg_registerdomain-none@2x.png");

    public static final Image bgRegisterMaskHover = new Image("image/bg_registermask_hover@2x.png");
    public static final Image bgHandOverMaskHover = new Image("image/bg_handovermask_hover@2x.png");
    public static final Image bgRegisterDomainHover = new Image("image/bg_registerdomain_hover@2x.png");

    public static final Image btnLeftBack = new Image("image/btn_back_card_none@2x.png");
    public static final Image btnLeftBackHover = new Image("image/btn_back_card_hover@2x.png");

    public static final Image btnPreGasUsed = new Image("image/btn_estimate@2x.png");
    public static final Image btnPreGasUsedHover = new Image("image/btn_estimate_click@2x.png");

    public static final Image icEstimateGasLimit = new Image("image/ic_estimate_gaslimit@2x.png");
    public static final Image icEstimateGasLimitHover = new Image("image/ic_estimate_gaslimit_hover@2x.png");

    public static final Image icCircleNone = new Image("image/ic_circle_grey@2x.png");
    public static final Image circleCrossGreyCheckBtn = new Image("image/ic_circle_cross_grey@2x.png");
    public static final Image circleCrossRedCheckBtn = new Image("image/ic_circle_cross_red@2x.png");
    public static final Image errorRed = new Image("image/ic_error_red@2x.png");
    public static final Image greenCheckBtn = new Image("image/ic_check@2x.png");
    public static final Image passwordPublic = new Image("image/ic_public@2x.png");
    public static final Image passwordPrivate = new Image("image/ic_private@2x.png");
    public static final Image keyboardBlack = new Image("image/ic_keyboard_black.png");
    public static final Image keyboardGray = new Image("image/ic_keyboard_gray.png");

    public static final Image icBackBlack = new Image("image/ic_back_b@2x.png");
    public static final Image icBackWhite = new Image("image/ic_back_w@2x.png");
    public static final Image icBackRed = new Image("image/ic_back_r@2x.png");

    public static final Image checkGrey = new Image("image/ledger_btn_uncheck@2x.png");
    public static final Image checkRed = new Image("image/ledger_btn_check@2x.png");

    public static final Image icPcClicked = new Image("image/btn_pc_click@2x.png");
    public static final Image icPcNotClicked = new Image("image/btn_pc_unclick@2x.png");
    public static final Image icMobileClicked = new Image("image/btn_mobile_click@2x.png");
    public static final Image icMobileNotClicked = new Image("image/btn_mobile_unclick@2x.png");
    public static final Image icBlankPagePc = new Image("image/ic_blankpage_pc@2x.png");
    public static final Image icBlankPageMobile = new Image("image/ic_blankpage_mobile@2x.png");

    public static final Image btnGasPlusGray = new Image("image/btn_gas_plus@2x.png");
    public static final Image btnGasMinusGray = new Image("image/btn_gas_minus@2x.png");
    public static final Image btnGasPlusBlack = new Image("image/btn_gas_plus_hover@2x.png");
    public static final Image btnGasMinusBlack = new Image("image/btn_gas_minus_hover@2x.png");

    public static ImageView imageViewRectangle30(ImageView imageView){
        Rectangle clip = new Rectangle(imageView.getFitWidth() - 0.5, imageView.getFitHeight() - 0.5);

        clip.setArcWidth(30);
        clip.setArcHeight(30);
        imageView.setClip(clip);

        return imageView;
    }


    public static Image getIdenticons(String address) {

        if(address == null || !AddressUtil.isAddress(address)){
            return ImageManager.icCircleNone;
        }else{
            return IdenticonGenerator.createIcon(address);
        }
    }
}
