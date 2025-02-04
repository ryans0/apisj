package org.apis.json;

import org.apis.core.Block;
import org.apis.util.ByteUtil;
import org.spongycastle.util.encoders.Hex;

import java.math.BigInteger;

public class BlockData {

    public long blockNumber;

    public String hash;

    public String parentHash;

    public String coinbase;

    public String stateRoot;

    public String txTrieHash;

    public String receiptsTrieHash;

    public String rewardPoint;

    public String gasLimit;

    public String mineralUsed;

    public String timestamp;

    public String extraData;

    public String rpSeed;

    public String nonce;

    public BlockData(Block block) {
        this.blockNumber = block.getNumber();
        this.hash = ByteUtil.toHexString(block.getHash());
        this.parentHash = ByteUtil.toHexString(block.getParentHash());
        this.coinbase = ByteUtil.toHexString(block.getCoinbase());
        this.stateRoot = ByteUtil.toHexString(block.getStateRoot());
        this.txTrieHash = ByteUtil.toHexString(block.getTxTrieRoot());
        this.receiptsTrieHash = ByteUtil.toHexString(block.getReceiptsRoot());
        this.rewardPoint = block.getRewardPoint().toString(10);
        this.gasLimit = new BigInteger(block.getGasLimit()).toString();
        this.mineralUsed = block.getMineralUsed().toString();
        this.timestamp = String.valueOf(block.getTimestamp());
        this.extraData = ByteUtil.toHexString(block.getExtraData());
        this.rpSeed = ByteUtil.toHexString(block.getMixHash());
        this.nonce = ByteUtil.toHexString(block.getNonce());
    }
}