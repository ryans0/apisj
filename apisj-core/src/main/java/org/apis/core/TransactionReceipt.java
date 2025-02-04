/*
 * Copyright (c) [2016] [ <ether.camp> ]
 * This file is part of the ethereumJ library.
 *
 * The ethereumJ library is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * The ethereumJ library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with the ethereumJ library. If not, see <http://www.gnu.org/licenses/>.
 */
package org.apis.core;

import org.apis.datasource.MemSizeEstimator;
import org.apis.util.*;
import org.apis.vm.LogInfo;
import org.apis.util.*;
import org.apis.vm.program.InternalTransaction;
import org.spongycastle.util.BigIntegers;
import org.spongycastle.util.encoders.Hex;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.apache.commons.lang3.ArrayUtils.nullToEmpty;
import static org.apis.datasource.MemSizeEstimator.ByteArrayEstimator;
import static org.apis.util.ByteUtil.EMPTY_BYTE_ARRAY;
import static org.apis.util.ByteUtil.toHexString;

/**
 * The transaction receipt is a tuple of three items
 * comprising the transaction, together with the post-transaction state,
 * and the cumulative gas used in the block containing the transaction receipt
 * as of immediately after the transaction has happened,
 */
public class TransactionReceipt {

    private Transaction transaction;

    private byte[] postTxState = EMPTY_BYTE_ARRAY;
    private byte[] cumulativeGas = EMPTY_BYTE_ARRAY;
    private byte[] cumulativeMineral = EMPTY_BYTE_ARRAY;
    private Bloom bloomFilter = new Bloom();
    private List<LogInfo> logInfoList = new ArrayList<>();
    private List<InternalTransaction> internalTransactionList = new ArrayList<>();

    private byte[] gasUsed = EMPTY_BYTE_ARRAY;
    private byte[] mineralUsed = EMPTY_BYTE_ARRAY;
    private byte[] executionResult = EMPTY_BYTE_ARRAY;
    private String error = "";

    /* Tx Receipt in encoded form */
    private byte[] rlpEncoded;

    public TransactionReceipt() {
    }

    public TransactionReceipt(byte[] rlp) {

        RLPList params = RLP.decode2(rlp);
        RLPList receipt = (RLPList) params.get(0);

        RLPItem postTxStateRLP = (RLPItem) receipt.get(0);
        RLPItem cumulativeGasRLP = (RLPItem) receipt.get(1);
        RLPItem cumulativeMineralRLP = (RLPItem) receipt.get(2);
        RLPItem bloomRLP = (RLPItem) receipt.get(3);
        RLPList logs = (RLPList) receipt.get(4);
        RLPList internalTransactions = (RLPList) receipt.get(5);
        RLPItem gasUsedRLP = (RLPItem) receipt.get(6);
        RLPItem mineralUsedRLP = (RLPItem) receipt.get(7);
        RLPItem result = (RLPItem) receipt.get(8);

        postTxState = nullToEmpty(postTxStateRLP.getRLPData());
        cumulativeGas = cumulativeGasRLP.getRLPData();
        cumulativeMineral = cumulativeMineralRLP.getRLPData();
        bloomFilter = new Bloom(bloomRLP.getRLPData());
        gasUsed = gasUsedRLP.getRLPData();
        mineralUsed = mineralUsedRLP.getRLPData();
        executionResult = (executionResult = result.getRLPData()) == null ? EMPTY_BYTE_ARRAY : executionResult;

        if (receipt.size() > 9) {
            byte[] errBytes = receipt.get(9).getRLPData();
            error = errBytes != null ? new String(errBytes, StandardCharsets.UTF_8) : "";
        }

        for (RLPElement log : logs) {
            LogInfo logInfo = new LogInfo(log.getRLPData());
            logInfoList.add(logInfo);
        }

        for(RLPElement internalTransaction : internalTransactions) {
            InternalTransaction itx = new InternalTransaction(internalTransaction.getRLPData());
            internalTransactionList.add(itx);
        }

        rlpEncoded = rlp;
    }


    public TransactionReceipt(byte[] postTxState, byte[] cumulativeGas, byte[] cumulativeMineral, Bloom bloomFilter, List<LogInfo> logInfoList, List<InternalTransaction> internalTransactionList) {
        this.postTxState = postTxState;
        this.cumulativeGas = cumulativeGas;
        this.cumulativeMineral = cumulativeMineral;
        this.bloomFilter = bloomFilter;
        this.logInfoList = logInfoList;
        this.internalTransactionList = internalTransactionList;
    }


    public TransactionReceipt(final RLPList rlpList) {
        if (rlpList == null || rlpList.size() != 5)
            throw new RuntimeException("Should provide RLPList with postTxState, cumulativeGas, bloomFilter, logInfoList");

        this.postTxState = rlpList.get(0).getRLPData();
        this.cumulativeGas = rlpList.get(1).getRLPData();
        this.cumulativeMineral = rlpList.get(2).getRLPData();
        this.bloomFilter = new Bloom(rlpList.get(3).getRLPData());

        List<LogInfo> logInfos = new ArrayList<>();
        for (RLPElement logInfoEl: (RLPList) rlpList.get(4)) {
            LogInfo logInfo = new LogInfo(logInfoEl.getRLPData());
            logInfos.add(logInfo);
        }
        this.logInfoList = logInfos;

        List<InternalTransaction> internalTransactions = new ArrayList<>();
        for(RLPElement internalTransaction : (RLPList) rlpList.get(5)) {
            InternalTransaction itx = new InternalTransaction(internalTransaction.getRLPData());
            internalTransactions.add(itx);
        }
        this.internalTransactionList = internalTransactions;
    }

    public byte[] getPostTxState() {
        return postTxState;
    }

    public byte[] getCumulativeGas() {
        return cumulativeGas;
    }

    public byte[] getCumulativeMineral() { return cumulativeMineral; }

    public byte[] getGasUsed() {
        return gasUsed;
    }

    public byte[] getMineralUsed() {
        return mineralUsed;
    }

    public byte[] getExecutionResult() {
        return executionResult;
    }

    public long getCumulativeGasLong() {
        return new BigInteger(1, cumulativeGas).longValue();
    }

    public BigInteger getCumulativeMineralBI() {
        return new BigInteger(1, cumulativeMineral);
    }


    public Bloom getBloomFilter() {
        return bloomFilter;
    }

    public List<LogInfo> getLogInfoList() {
        return logInfoList;
    }

    public List<InternalTransaction> getInternalTransactionList() {
        return internalTransactionList;
    }

    public boolean isValid() {
        return ByteUtil.byteArrayToLong(gasUsed) > 0;
    }

    public boolean isSuccessful() {
        return error.isEmpty();
    }

    public String getError() {
        return error;
    }

    /**
     *  Used for Receipt trie hash calculation. Should contain only the following items encoded:
     *  [postTxState, cumulativeGas, bloomFilter, logInfoList]
     */
    public byte[] getReceiptTrieEncoded() {
        return getEncoded(true);
    }

    /**
     * Used for serialization, contains all the receipt data encoded
     */
    public byte[] getEncoded() {
        if (rlpEncoded == null) {
            rlpEncoded = getEncoded(false);
        }

        return rlpEncoded;
    }

    public byte[] getEncoded(boolean receiptTrie) {

        byte[] postTxStateRLP = RLP.encodeElement(this.postTxState);
        byte[] cumulativeGasRLP = RLP.encodeElement(this.cumulativeGas);
        byte[] cumulativeMineralRLP = RLP.encodeElement(this.cumulativeMineral);
        byte[] bloomRLP = RLP.encodeElement(this.bloomFilter.data);

        final byte[] logInfoListRLP;
        if (logInfoList != null) {
            byte[][] logInfoListE = new byte[logInfoList.size()][];

            int i = 0;
            for (LogInfo logInfo : logInfoList) {
                logInfoListE[i] = logInfo.getEncoded();
                ++i;
            }
            logInfoListRLP = RLP.encodeList(logInfoListE);
        } else {
            logInfoListRLP = RLP.encodeList();
        }

        final byte[] internalTransactionListRLP;
        if (internalTransactionList != null) {
            byte[][] internalTransactionListE = new byte[internalTransactionList.size()][];

            int i = 0;
            for(InternalTransaction itx : internalTransactionList) {
                internalTransactionListE[i] = itx.getEncoded();
                ++i;
            }
            internalTransactionListRLP = RLP.encodeList(internalTransactionListE);
        } else {
            internalTransactionListRLP = RLP.encodeList();
        }

        return receiptTrie ?
                RLP.encodeList(postTxStateRLP, cumulativeGasRLP, cumulativeMineralRLP, bloomRLP, logInfoListRLP, internalTransactionListRLP):
                RLP.encodeList(postTxStateRLP, cumulativeGasRLP, cumulativeMineralRLP, bloomRLP, logInfoListRLP, internalTransactionListRLP,
                        RLP.encodeElement(gasUsed), RLP.encodeElement(mineralUsed), RLP.encodeElement(executionResult),
                        RLP.encodeElement(error.getBytes(StandardCharsets.UTF_8)));
    }

    public void setPostTxState(byte[] postTxState) {
        this.postTxState = postTxState;
        rlpEncoded = null;
    }

    public void setTxStatus(boolean success) {
        this.postTxState = success ? new byte[]{1} : new byte[0];
        rlpEncoded = null;
    }

    public boolean hasTxStatus() {
        return postTxState != null && postTxState.length <= 1;
    }

    public boolean isTxStatusOK() {
        return postTxState != null && postTxState.length == 1 && postTxState[0] == 1;
    }

    public void setCumulativeGas(long cumulativeGas) {
        this.cumulativeGas = BigIntegers.asUnsignedByteArray(BigInteger.valueOf(cumulativeGas));
        rlpEncoded = null;
    }

    public void setCumulativeGas(byte[] cumulativeGas) {
        this.cumulativeGas = cumulativeGas;
        rlpEncoded = null;
    }

    public void setCumulativeMineral(BigInteger cumulativeMineral) {
        this.cumulativeMineral = cumulativeMineral.toByteArray();
        rlpEncoded = null;
    }

    public void setGasUsed(byte[] gasUsed) {
        this.gasUsed = gasUsed;
        rlpEncoded = null;
    }

    public void setMineralUsed(byte[] mineralUsed) {
        this.mineralUsed = mineralUsed;
        rlpEncoded = null;
    }

    public void setGasUsed(long gasUsed) {
        this.gasUsed = BigIntegers.asUnsignedByteArray(BigInteger.valueOf(gasUsed));
        rlpEncoded = null;
    }

    public void setMineralUsed(BigInteger mineralUsed) {
        this.mineralUsed = BigIntegers.asUnsignedByteArray(mineralUsed);
        rlpEncoded = null;
    }

    public void setExecutionResult(byte[] executionResult) {
        this.executionResult = executionResult;
        rlpEncoded = null;
    }

    public void setError(String error) {
        this.error = error == null ? "" : error;
    }

    public void setLogInfoList(List<LogInfo> logInfoList) {
        if (logInfoList == null) return;
        this.logInfoList = logInfoList;

        for (LogInfo loginfo : logInfoList) {
            bloomFilter.or(loginfo.getBloom());
        }
        rlpEncoded = null;
    }

    public void setInternalTransactionList(List<InternalTransaction> internalTxList) {
        if (internalTxList == null) return;
        this.internalTransactionList = internalTxList;
        rlpEncoded = null;
    }

    public void setTransaction(Transaction transaction) {
        this.transaction = transaction;
    }

    public Transaction getTransaction() {
        if (transaction == null) throw new NullPointerException("Transaction is not initialized. Use TransactionInfo and BlockStore to setup Transaction instance");
        return transaction;
    }

    @Override
    public String toString() {

        // todo: fix that
        return "TransactionReceipt[" +
                "\n  , " + (hasTxStatus() ? ("txStatus=" + (isTxStatusOK() ? "OK" : "FAILED"))
                                        : ("postTxState=" + Hex.toHexString(postTxState))) +
                "\n  , cumulativeGas=" + Hex.toHexString(cumulativeGas) +
                "\n  , cumulativeMineral=" + Hex.toHexString(cumulativeMineral) +
                "\n  , gasUsed=" + Hex.toHexString(gasUsed) +
                "\n  , gasLimit=" + Hex.toHexString(transaction.getGasLimit()) +
                "\n  , gasPrice=" + Hex.toHexString(transaction.getGasPrice()) +
                "\n  , mineralUsed=" + Hex.toHexString(mineralUsed) +
                "\n  , error=" + error +
                "\n  , executionResult=" + Hex.toHexString(executionResult) +
                "\n  , bloom=" + bloomFilter.toString() +
                "\n  , logs=" + logInfoList +
                "\n  , internalTxs=" + internalTransactionList +
                ']';
    }

    public long estimateMemSize() {
        return MemEstimator.estimateSize(this);
    }

    public static final MemSizeEstimator<TransactionReceipt> MemEstimator = receipt -> {
        if (receipt == null) {
            return 0;
        }
        long logSize = receipt.logInfoList.stream().mapToLong(LogInfo.MemEstimator::estimateSize).sum() + 16;
        return (receipt.transaction == null ? 0 : Transaction.MemEstimator.estimateSize(receipt.transaction)) +
                (receipt.postTxState == EMPTY_BYTE_ARRAY ? 0 : ByteArrayEstimator.estimateSize(receipt.postTxState)) +
                (receipt.cumulativeGas == EMPTY_BYTE_ARRAY ? 0 : ByteArrayEstimator.estimateSize(receipt.cumulativeGas)) +
                (receipt.gasUsed == EMPTY_BYTE_ARRAY ? 0 : ByteArrayEstimator.estimateSize(receipt.gasUsed)) +
                (receipt.executionResult == EMPTY_BYTE_ARRAY ? 0 : ByteArrayEstimator.estimateSize(receipt.executionResult)) +
                ByteArrayEstimator.estimateSize(receipt.rlpEncoded) +
                Bloom.MEM_SIZE +
                receipt.error.getBytes().length + 40 +
                logSize;
    };
}
