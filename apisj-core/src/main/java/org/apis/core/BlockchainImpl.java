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
 *
 * Original Code
 * https://github.com/ethereum/ethereumj/blob/develop/ethereumj-core/src/main/java/org/ethereum/core/BlockchainImpl.java
 * Modified by APIS
 */
package org.apis.core;

import org.apache.commons.lang3.tuple.Pair;
import org.apis.config.CommonConfig;
import org.apis.config.Constants;
import org.apis.config.SystemProperties;
import org.apis.contract.ContractLoader;
import org.apis.crypto.HashUtil;
import org.apis.datasource.inmem.HashMapDB;
import org.apis.db.*;
import org.apis.listener.EthereumListener;
import org.apis.listener.EthereumListenerAdapter;
import org.apis.manager.AdminInfo;
import org.apis.mine.MinedBlockCache;
import org.apis.sync.SyncManager;
import org.apis.trie.Trie;
import org.apis.trie.TrieImpl;
import org.apis.util.*;
import org.apis.util.blockchain.MasternodeRewardData;
import org.apis.validator.DependentBlockHeaderRule;
import org.apis.validator.ParentBlockHeaderValidator;
import org.apis.vm.LogInfo;
import org.apis.vm.program.invoke.ProgramInvokeFactory;
import org.apis.vm.program.invoke.ProgramInvokeFactoryImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigInteger;
import java.util.*;

import static java.lang.Runtime.getRuntime;
import static java.math.BigInteger.ONE;
import static java.math.BigInteger.ZERO;
import static java.util.Collections.emptyList;
import static org.apis.core.Denomination.nAPIS;
import static org.apis.core.ImportResult.*;
import static org.apis.crypto.HashUtil.sha3;

/**
 * The APIS blockchain is in many ways similar to the Apis blockchain,
 * although it does have some differences.
 * <p>
 * The main difference between APIS and Apis with regard to the blockchain architecture
 * is that, unlike Apis, APIS blocks remove uncle blocks and contain sum of used mineral
 * </p>
 * The block validation algorithm in APIS is as follows:
 * <ol>
 * <li>Check if the previous block referenced exists and is valid.</li>
 * <li>Check that the timestamp of the block is greater than that of the referenced previous block and less than 10 minutes into the future</li>
 * <li>Check that the block number, difficulty, transaction root and gas limit are valid.</li>
 * <li>Check that the proof of work on the block is valid.</li>
 * <li>Let S[0] be the STATE_ROOT of the previous block.</li>
 * <li>Let TX be the block's transaction list, with n transactions.
 * For all in in 0...n-1, set S[i+1] = APPLY(S[i],TX[i]).
 * If any applications returns an error, or if the total gas consumed in the block
 * up until this point exceeds the GAS_LIMIT, return an error.</li>
 * <li>Let S_FINAL be S[n], but adding the block reward paid to the miner and master-nodes.</li>
 * <li>Check if S_FINAL is the same as the STATE_ROOT. If it is, the block is valid; otherwise, it is not valid.</li>
 * </ol>
 *
 * @author Roman Mandeleil
 * @author Nick Savers
 * @author Daniel
 * @since 20.05.2014
 */
@Component
public class BlockchainImpl implements Blockchain, org.apis.facade.Blockchain {

    private static final Logger logger = LoggerFactory.getLogger("blockchain");
    private static final Logger stateLogger = LoggerFactory.getLogger("state");

    // to avoid using minGasPrice=0 from Genesis for the wallet
     private static final long INITIAL_MIN_GAS_PRICE = 10 * nAPIS.longValue();
    public static final byte[] EMPTY_LIST_HASH = sha3(RLP.encodeList(new byte[0]));

    @Autowired @Qualifier("defaultRepository")
    private Repository repository;

    @Autowired
    protected BlockStore blockStore;

    private HeaderStore headerStore = null;

    @Autowired
    private TransactionStore transactionStore;

    private Block bestBlock;

    private BigInteger totalRewardPoint = ZERO;

    @Autowired
    private EthereumListener listener;

    @Autowired
    private
    ProgramInvokeFactory programInvokeFactory;

    @Autowired
    private AdminInfo adminInfo;

    @Autowired
    private DependentBlockHeaderRule parentHeaderValidator;

    @Autowired
    private PendingState pendingState;

    @Autowired
    private
    EventDispatchThread eventDispatchThread;

    @Autowired
    private
    CommonConfig commonConfig = CommonConfig.getDefault();

    @Autowired
    private
    SyncManager syncManager;

    @Autowired
    private
    PruneManager pruneManager;

    @Autowired
    StateSource stateDataSource;

    @Autowired
    private
    DbFlushManager dbFlushManager;

    SystemProperties config = SystemProperties.getDefault();

    private List<Chain> altChains = new ArrayList<>();
    private List<Block> garbage = new ArrayList<>();

    private long exitOn = Long.MAX_VALUE;

    public boolean byTest = false;
    private boolean fork = false;

    private byte[] minerCoinbase;
    private byte[] minerExtraData;


    private Stack<State> stateStack = new Stack<>();

    /** Tests only **/
    public BlockchainImpl() {
    }

    @Autowired
    public BlockchainImpl(final SystemProperties config) {
        this.config = config;
        initConst(config);
    }

    //todo: autowire over constructor
    public BlockchainImpl(final BlockStore blockStore, final Repository repository) {
        this.blockStore = blockStore;
        this.repository = repository;
        this.adminInfo = new AdminInfo();
        this.listener = new EthereumListenerAdapter();
        this.parentHeaderValidator = null;
        this.transactionStore = new TransactionStore(new HashMapDB());
        this.eventDispatchThread = EventDispatchThread.getDefault();
        this.programInvokeFactory = new ProgramInvokeFactoryImpl();
        initConst(SystemProperties.getDefault());
    }

    public BlockchainImpl withTransactionStore(TransactionStore transactionStore) {
        this.transactionStore = transactionStore;
        return this;
    }

    public BlockchainImpl withAdminInfo(AdminInfo adminInfo) {
        this.adminInfo = adminInfo;
        return this;
    }

    public BlockchainImpl withEthereumListener(EthereumListener listener) {
        this.listener = listener;
        return this;
    }

    public BlockchainImpl withSyncManager(SyncManager syncManager) {
        this.syncManager = syncManager;
        return this;
    }

    public BlockchainImpl withParentBlockHeaderValidator(ParentBlockHeaderValidator parentHeaderValidator) {
        this.parentHeaderValidator = parentHeaderValidator;
        return this;
    }

    private void initConst(SystemProperties config) {
        minerCoinbase = config.getMinerCoinbase();
        minerExtraData = config.getMineExtraData();
    }

    @Override
    public byte[] getBestBlockHash() {
        return getBestBlock().getHash();
    }

    @Override
    public long getSize() {
        return bestBlock.getNumber() + 1;
    }

    @Override
    public Block getBlockByNumber(long blockNr) {
        return blockStore.getChainBlockByNumber(blockNr);
    }

    @Override
    public TransactionInfo getTransactionInfo(byte[] hash) {

        List<TransactionInfo> infoList = transactionStore.get(hash);

        if (infoList == null || infoList.isEmpty())
            return null;

        /* 트랜잭션이 하나만 존재하는 경우, 해당하는 정보를 반환하고
         * 다수가 검색될 경우, 메인 체인에 등록된 트랜잭션을 반환한다. */
        TransactionInfo txInfo = null;
        if (infoList.size() == 1) {
            txInfo = infoList.get(0);
        } else {
            for (TransactionInfo info : infoList) {
                Block block = blockStore.getBlockByHash(info.blockHash);
                Block mainBlock = blockStore.getChainBlockByNumber(block.getNumber());
                if (FastByteComparisons.equal(info.blockHash, mainBlock.getHash())) {
                    txInfo = info;
                    break;
                }
            }
        }
        if (txInfo == null) {
            logger.warn("Can't find block from main chain for transaction " + Hex.toHexString(hash));
            return null;
        }

        Transaction tx = this.getBlockByHash(txInfo.getBlockHash()).getTransactionsList().get(txInfo.getIndex());
        txInfo.setTransaction(tx);

        return txInfo;
    }

    @Override
    public Block getBlockByHash(byte[] hash) {
        return blockStore.getBlockByHash(hash);
    }

    @Override
    public synchronized List<byte[]> getListOfHashesStartFrom(byte[] hash, int qty) {
        return blockStore.getListHashesEndWith(hash, qty);
    }

    @Override
    public synchronized List<byte[]> getListOfHashesStartFromBlock(long blockNumber, int qty) {
        long bestNumber = bestBlock.getNumber();

        if (blockNumber > bestNumber) {
            return emptyList();
        }

        if (blockNumber + qty - 1 > bestNumber) {
            qty = (int) (bestNumber - blockNumber + 1);
        }

        long endNumber = blockNumber + qty - 1;

        Block block = getBlockByNumber(endNumber);

        List<byte[]> hashes = blockStore.getListHashesEndWith(block.getHash(), qty);

        // asc order of hashes is required in the response
        Collections.reverse(hashes);

        return hashes;
    }

    static byte[] calcTxTrie(List<Transaction> transactions) {

        Trie txsState = new TrieImpl();

        if (transactions == null || transactions.isEmpty())
            return HashUtil.EMPTY_TRIE_HASH;

        for (int i = 0; i < transactions.size(); i++) {
            txsState.put(RLP.encodeInt(i), transactions.get(i).getEncoded());
        }
        return txsState.getRootHash();
    }

    static byte[] calcMnHash(List<byte[]> ... mnList) {
        int totalLength = 0;
        for (List<byte[]> list : mnList) {
            totalLength += list.size();
        }
        byte[][] merge = new byte[totalLength][];

        int i = 0;
        for (List<byte[]> aMnList : mnList) {
            for(byte[] mn : aMnList) {
                merge[i] = mn;
                ++i;
            }
        }

        return HashUtil.sha3(ByteUtil.merge(merge));
    }

    public Repository getRepository() {
        return repository;
    }

    public Repository getRepositorySnapshot() {
        return repository.getSnapshotTo(blockStore.getBestBlock().getStateRoot());
    }

    @Override
    public BlockStore getBlockStore() {
        return blockStore;
    }

    public ProgramInvokeFactory getProgramInvokeFactory() {
        return programInvokeFactory;
    }

    private State pushState(byte[] bestBlockHash) {
        State push = stateStack.push(new State());
        this.bestBlock = blockStore.getBlockByHash(bestBlockHash);
        totalRewardPoint = blockStore.getTotalRewardPointForHash(bestBlockHash);
        this.repository = this.repository.getSnapshotTo(this.bestBlock.getStateRoot());
        return push;
    }

    private void popState() {
        State state = stateStack.pop();
        this.repository = repository.getSnapshotTo(state.root);
        this.bestBlock = state.savedBest;
        this.totalRewardPoint = state.savedTotalRewardPoint;
    }

    public void dropState() {
        stateStack.pop();
    }

    /**
     * 새로운 블록을 전달받았는데 조상들 중에 부모가 존재하는 경우
     * 판단에 따라서 갈라서게 될 수도 있다.
     */
    private synchronized BlockSummary tryConnectAndFork(final Block block) {
        Block oldBestBlock = blockStore.getChainBlockByNumber(block.getNumber());
        BigInteger oldRP = BigInteger.ZERO;
        if(oldBestBlock != null) {
            oldRP = oldBestBlock.getCumulativeRewardPoint();
        }

        State savedState = pushState(block.getParentHash());
        this.fork = true;

        final BlockSummary summary;
        Repository repo;
        try {
            // FIXME: adding block with no option for flush
            Block parentBlock = getBlockByHash(block.getParentHash());
            repo = repository.getSnapshotTo(parentBlock.getStateRoot());
            summary = add(repo, block);
            if (summary == null) {
                return null;
            }
        } catch (Throwable th) {
            logger.error("Unexpected error: ", th);
            return null;
        } finally {
            this.fork = false;
        }

        // 새로운 블록의 TotalRewardPoint 값이 더 크면 fork
        //if (summary.betterThan(bestBlock.getCumulativeRewardPoint())) {
        if(oldRP.compareTo(block.getCumulativeRewardPoint()) < 0) {

            logger.info("Rebranching: {} ~> {}", savedState.savedBest.getShortHash(), block.getShortHash());

            // main branch become this branch
            // cause we proved that total difficulty
            // is greateer
            blockStore.reBranch(block);

            // The main repository rebranch
            this.repository = repo;
//            this.repository.syncToRoot(block.getStateRoot());

            dropState();
        } else {
            // Stay on previous branch
            popState();
        }

        return summary;
    }


    public synchronized ImportResult tryToConnect(final Block block) {

        if (logger.isDebugEnabled())
            logger.debug("Try connect block hash: {}, number: {}",
                    Hex.toHexString(block.getHash()).substring(0, 6),
                    block.getNumber());

        // 받은 블록이 이미 존재하면 연결하지 않는다
        if (blockStore.getMaxNumber() >= block.getNumber() && blockStore.isBlockExist(block.getHash())) {
            if (logger.isDebugEnabled())
                logger.debug("Block already exist hash: {}, number: {}",
                        Hex.toHexString(block.getHash()).substring(0, 6),
                        block.getNumber());

            // retry of well known block
            return EXIST;
        }

        final ImportResult ret;

        // The simple case got the block to connect to the main chain
        final BlockSummary summary;

        // 새로 받은 블록이 마지막 블록의 자식 블록일 경우
        // 가장 먼저 채굴된 블록일 경우
        if (bestBlock.isParentOf(block)) {
            recordBlock(block);
            Block parentBlock = getBlockByHash(block.getParentHash());
            Repository repo = repository.getSnapshotTo(parentBlock.getStateRoot());
            summary = add(repo, block);

            if(summary == null) {
                ret = INVALID_BLOCK;
            } else {
                ret = IMPORTED_BEST;
            }
        }

        else {
            if(blockStore.isBlockExist(block.getParentHash())) {
                Block storedBlock = blockStore.getChainBlockByNumber(block.getNumber());
                BigInteger oldTotalRP = BigInteger.ZERO;
                if(storedBlock != null) {
                    oldTotalRP = storedBlock.getCumulativeRewardPoint();
                }

                recordBlock(block);
                summary = tryConnectAndFork(block);

                ret = (summary == null ? INVALID_BLOCK : (summary.betterThan(oldTotalRP) ? IMPORTED_BEST : IMPORTED_NOT_BEST));
            } else {
                summary = null;
                ret = NO_PARENT;
            }
        }


        if (ret.isSuccessful()) {
            listener.onBlock(summary);
            listener.trace(String.format("Block chain size: [ %d ]", this.getSize()));

            if (ret == IMPORTED_BEST) {
                //pendingState.processBest(block, summary.getReceipts());
                eventDispatchThread.invokeLater(() -> pendingState.processBest(block, summary.getReceipts()));
            }
        }
        else if(ret.equals(INVALID_BLOCK)) {
            MinedBlockCache cache = MinedBlockCache.getInstance();
            cache.removeBestBlock(block);
        }

        return ret;
    }

    public synchronized Block createNewBlock(Block parent, List<Transaction> txs) {
        long now = TimeUtils.getRealTimestamp();
        Constants constants = config.getBlockchainConfig().getConfigForBlock(parent.getNumber()).getConstants();
        long timestamp;

        // 직전 블록 생성 시간에서 블록타임만큼 지나지 않았다면 새로운 블록을 생성할 시간에 도달하지 않았으므로 스킵
        if (now - parent.getTimestamp()*1000L < (constants.getBLOCK_TIME_MS() - 2_000L)) {
            return null;
        }

        if(now - parent.getTimestamp()*1000 < constants.getBLOCK_TIME_MS()*2) {
            timestamp = parent.getTimestamp() + constants.getBLOCK_TIME();
        } else {
            timestamp = now / 1000L;
        }

        return createNewBlock(parent, txs, timestamp);
    }



    public synchronized Block createNewBlock(Block parent, List<Transaction> txs, long time) {
        Repository track = repository.getSnapshotTo(parent.getStateRoot());

        final long blockNumber = parent.getNumber() + 1;

        final byte[] extraData = config.getBlockchainConfig().getConfigForBlock(blockNumber).getExtraData(minerExtraData, blockNumber);

        Block block = new Block(parent.getHash(),
                config.getCoinbaseKey().getAddress(),
                new byte[0], // log bloom - from tx receipts
                BigInteger.ZERO, // RewardPoint
                BigInteger.ZERO,
                blockNumber,
                ByteUtil.bigIntegerToBytes(config.getBlockchainConfig().getConfigForBlock(blockNumber).getBlockGasLimit()), // Gas Limit
                0,  // gas used - computed after running all transactions
                BigInteger.ZERO,    // mineral used - computed after running all transactions
                time,  // block time
                extraData,  // extra data
                new byte[0],  // mixHash (to mine)
                new byte[0],  // nonce   (to mine)
                new byte[0],  // receiptsRoot - computed after running all transactions
                calcTxTrie(txs),    // TransactionsRoot - computed after running all transactions
                new byte[] {0}, // stateRoot - computed after running all transactions
                BigInteger.ZERO,// mnReward
                new byte[0],    // mnHash
                txs,
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList());




        // 블록의 RewardPoint를 계산한다.
        Repository repo = RewardPointUtil.getRewardPointBalanceRepo(repository, parent, blockStore);
        BigInteger balance = repo.getBalance(block.getCoinbase());
        byte[] seed = RewardPointUtil.calcSeed(block.getCoinbase(), balance, parent.getHash());
        BigInteger rp = RewardPointUtil.calcRewardPoint(seed, balance);
        BigInteger cumulativeRP = parent.getCumulativeRewardPoint().add(rp);

        block.setNonce(ByteUtil.bigIntegerToBytes(balance));
        block.setMixHash(seed);
        block.setRewardPoint(rp);
        block.setCumulativeRewardPoint(cumulativeRP);


        // Start distributing reward to the MNs vvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvvv
        final Constants constants = config.getBlockchainConfig().getConfigForBlock(blockNumber).getConstants();
        if(constants.isMasternodeRewardBlock(blockNumber)) {

            // 각 이자받는 마스터노드 갯수를 구한다.
            List<byte[]> generalEarlybird   = track.getMasterNodeList(constants.getMASTERNODE_GENERAL_BASE_EARLY_RUN());
            List<byte[]> generalNormal      = track.getMasterNodeList(constants.getMASTERNODE_GENERAL_BASE_NORMAL());
            List<byte[]> generalLate        = track.getMasterNodeList(constants.getMASTERNODE_GENERAL_BASE_LATE(), blockNumber);

            List<byte[]> majorEarlybird     = track.getMasterNodeList(constants.getMASTERNODE_MAJOR_BASE_EARLY_RUN());
            List<byte[]> majorNormal        = track.getMasterNodeList(constants.getMASTERNODE_MAJOR_BASE_NORMAL());
            List<byte[]> majorLate          = track.getMasterNodeList(constants.getMASTERNODE_MAJOR_BASE_LATE(), blockNumber);

            List<byte[]> privateEarlybird   = track.getMasterNodeList(constants.getMASTERNODE_PRIVATE_BASE_EARLY_RUN());
            List<byte[]> privateNormal      = track.getMasterNodeList(constants.getMASTERNODE_PRIVATE_BASE_NORMAL());
            List<byte[]> privateLate        = track.getMasterNodeList(constants.getMASTERNODE_PRIVATE_BASE_LATE(), blockNumber);

            List<byte[]> allGeneralNormal = new ArrayList<>();
            allGeneralNormal.addAll(generalEarlybird);
            allGeneralNormal.addAll(generalNormal);

            List<byte[]> allGeneralLate = new ArrayList<>(generalLate);

            List<byte[]> allMajorNormal = new ArrayList<>();
            allMajorNormal.addAll(majorEarlybird);
            allMajorNormal.addAll(majorNormal);

            List<byte[]> allMajorLate = new ArrayList<>(majorLate);

            List<byte[]> allPrivateNormal = new ArrayList<>();
            allPrivateNormal.addAll(privateEarlybird);
            allPrivateNormal.addAll(privateNormal);

            List<byte[]> allPrivateLate = new ArrayList<>(privateLate);


            long countGeneral = generalEarlybird.size() + generalNormal.size() + generalLate.size();
            long countMajor = majorEarlybird.size() + majorNormal.size() + majorLate.size();
            long countPrivate = privateEarlybird.size() + privateNormal.size() + privateLate.size();

            BigInteger storedMasternodeReward = track.getBalance(constants.getMASTERNODE_STORAGE());

            if(countGeneral > 0 || countMajor > 0 || countPrivate > 0) {
                BigInteger amountGeneral = MasternodeRewardUtil.calcBasicReward(constants, storedMasternodeReward, countGeneral, countMajor, countPrivate);

                block.setMnGeneralList(allGeneralNormal);
                block.setMnMajorList(allMajorNormal);
                block.setMnPrivateList(allPrivateNormal);
                block.setMnGeneralLateList(allGeneralLate);
                block.setMnMajorLateList(allMajorLate);
                block.setMnPrivateLateList(allPrivateLate);
                block.setMnHash(calcMnHash(generalEarlybird, generalNormal, generalLate, majorEarlybird, majorNormal, majorLate, privateEarlybird, privateNormal, privateLate));
                block.setMnReward(amountGeneral);
            }
        }
        // Finish distributing reward to the MNs ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^


        // 블록 내용을 실행-
        BlockSummary summary = applyBlock(track, block);
        List<TransactionReceipt> receipts = summary.getReceipts();
        block.setStateRoot(track.getRoot());

        Bloom logBloom = new Bloom();
        for (TransactionReceipt receipt : receipts) {
            logBloom.or(receipt.getBloomFilter());
        }
        block.getHeader().setLogsBloom(logBloom.getData());
        block.getHeader().setGasUsed(receipts.size() > 0 ? receipts.get(receipts.size() - 1).getCumulativeGasLong() : 0);
        block.getHeader().setMineralUsed(receipts.size() > 0 ? receipts.get(receipts.size() - 1).getCumulativeMineralBI() : BigInteger.ZERO);
        block.getHeader().setReceiptsRoot(calcReceiptsTrie(receipts));

        /* 블록에도 채굴자의 서명을 기입하도록 변경하였다.
         * 왜냐하면, 채굴의 우선순위를 정하는 RP 값은 채굴자의 잔고에 따라서 달라지는데
         * 악의적인 사용자가 RP 값이 높은 주소로 블록을 생성함으로써
         * transaction을 조작할 여지가 존재한다고 판단했기 때문 */
        block.getHeader().sign(config.getCoinbaseKey());

        return block;
    }

    @Override
    public BlockSummary add(Block block) {
        throw new RuntimeException("Not supported");
    }

    //    @Override
    public synchronized BlockSummary add(Repository repo, final Block block) {
        BlockSummary summary = addImpl(repo, block);

        if (summary == null) {
            stateLogger.warn("Trying to reimport the block for debug...");

            try { Thread.sleep(50); } catch (InterruptedException e) { e.printStackTrace(); }

            BlockSummary summary1 = addImpl(repo.getSnapshotTo(getBestBlock().getStateRoot()), block);
            stateLogger.warn("Second import trial " + (summary1 == null ? "FAILED" : "OK"));
            if (summary1 != null) {
                if (config.exitOnBlockConflict()) {
                    stateLogger.error("Inconsistent behavior, exiting...");
                    System.exit(-1);
                } else {
                    return summary1;
                }
            }
        }
        return summary;
    }

    private synchronized BlockSummary addImpl(Repository repo, final Block block) {

        if (block == null)
            return null;

        if (block.getNumber() > exitOn) {
            System.out.print("Exiting after block.number: " + bestBlock.getNumber());
            dbFlushManager.flushSync();
            System.exit(-1);
        }

        if (!isValid(repo, block)) {
            logger.warn("Invalid block with number: {}", block.getNumber());
            return null;
        }

        Constants constants = config.getBlockchainConfig().getConfigForBlock(block.getNumber()).getConstants();

//        Repository track = repo.startTracking();
        byte[] origRoot = repo.getRoot();


        // keep chain continuity
//        if (!Arrays.equals(bestBlock.getHash(),
//                block.getParentHash())) return null;

        if (block.getNumber() >= config.traceStartBlock() && config.traceStartBlock() != -1) {
            AdvancedDeviceUtils.adjustDetailedTracing(config, block.getNumber());
        }

        BlockSummary summary = processBlock(repo, block);
        final List<TransactionReceipt> receipts = summary.getReceipts();

        // Sanity checks

        if (!FastByteComparisons.equal(block.getReceiptsRoot(), calcReceiptsTrie(receipts))) {
            logger.warn("Block's given Receipt Hash doesn't match: {} != {}", Hex.toHexString(block.getReceiptsRoot()), Hex.toHexString(calcReceiptsTrie(receipts)));
            //logger.warn("Calculated receipts: " + receipts);
            repo.rollback();
            summary = null;
        }

        if (!FastByteComparisons.equal(block.getLogBloom(), calcLogBloom(receipts))) {
            logger.warn("Block's given logBloom Hash doesn't match: {} != {}", Hex.toHexString(block.getLogBloom()), Hex.toHexString(calcLogBloom(receipts)));
            repo.rollback();
            summary = null;
        }

        Block parentBlock = blockStore.getBlockByHash(block.getParentHash());

        Block balanceBlock = parentBlock;
        for(int i = 0 ; i < 10 ; i++) {
            if(balanceBlock.getNumber() > 0) {
                balanceBlock = getBlockByHash(balanceBlock.getParentHash());
            } else {
                break;
            }
        }
        Repository balanceRepo = repo.getSnapshotTo(balanceBlock.getStateRoot());

        BigInteger balance = balanceRepo.getBalance(block.getCoinbase());
        byte[] seed = RewardPointUtil.calcSeed(block.getCoinbase(), balance, block.getParentHash());
        BigInteger calculatedRP = RewardPointUtil.calcRewardPoint(seed, balance);
        //RewardPoint calculatedRP = RewardPointUtil.genRewardPoint(parentBlock, block.getCoinbase(), parentRepo);


        if(!FastByteComparisons.equal(block.getNonce(), ByteUtil.bigIntegerToBytes(balance))) {
            logger.warn("Block({})'s given nonce doesn't match: {} != {}",block.getNumber(), Hex.toHexString(block.getNonce()), Hex.toHexString(ByteUtil.bigIntegerToBytes(balance)));
            repo.rollback();
            summary = null;
        }

        if(!FastByteComparisons.equal(block.getMixHash(), seed)) {
            logger.warn("Block[{}]'s given RP seed doesn't match: {} != {}", block.getNumber(), Hex.toHexString(block.getMixHash()), Hex.toHexString(seed));
            repo.rollback();
            summary = null;
        }

        // Verify reward-point
        if(BIUtil.isNotEqual(block.getRewardPoint(), calculatedRP)) {
            logger.info("{} | {}", Hex.toHexString(parentBlock.getStateRoot()), Hex.toHexString(repo.getRoot()));

            logger.info("Block({})'s given nonce : {} != {}",block.getNumber(), Hex.toHexString(block.getNonce()), Hex.toHexString(ByteUtil.bigIntegerToBytes(balance)));
            logger.warn("Block[{}]'s given RP seed : {} != {}", block.getNumber(), Hex.toHexString(block.getMixHash()), Hex.toHexString(seed));
            logger.warn("Block's given rewardPoint doesn't match: {} != {}", block.getRewardPoint().toString(), calculatedRP.toString());
            repo.rollback();
            summary = null;
        }

        // Verify cumulative RP
        if(BIUtil.isNotEqual(block.getCumulativeRewardPoint(), parentBlock.getCumulativeRewardPoint().add(calculatedRP))) {
            logger.warn("Block's given cumulativeRewardPoint doesn't match: {} != {}", block.getCumulativeRewardPoint().toString(), parentBlock.getCumulativeRewardPoint().add(calculatedRP));
            repo.rollback();
            summary = null;
        }

        // 마스터노드는 블록을 생성할 수 없다
        if(repo.isIncludedInMasternodes(block.getCoinbase())) {
            logger.warn("The master node({}) can not create a block.", ByteUtil.toHexString(block.getCoinbase()));
            repo.rollback();
            summary = null;
        }

        // 자식 블록은 부모 블록이 생성된 시간 + 블록타임(8초) 마다 생성되어야 한다.
        // 따라서 부모가 생성된 이후에 블록타임(8초) 이내에 생성된 블록은 비정상적인 블록이다.
        if(block.getTimestamp() < parentBlock.getTimestamp() + constants.getBLOCK_TIME()) {
            logger.warn("Block creation time is too fast.\n: {} < {} + 10", block.getTimestamp(), parentBlock.getTimestamp());
            repo.rollback();
            summary = null;
        }

        // 블록이 생성된 시간이 미래의 시점일 경우 비정상적인 블록이다.
        long now = TimeUtils.getRealTimestamp() + 500;
        if(block.getTimestamp()*1_000L > now) {
            logger.warn("Block was created in the future.\n.\n: Block's timestamp : {} > Real Timestamp {}", block.getTimestamp()*1_000L, now);
            repo.rollback();
            summary = null;
        }


        // 인접한 조상과 동일한 coinbase를 갖는 블럭은 체인에 연결할 수 없다.
        // Mainnet 에서만 3블록 연속 체굴을 제약한다. 테스트넷에는 해당 없음
        if(config.getBlockchainConfig().getConfigForBlock(0).getChainId() == 1 && block.getNumber() > 33) {
            long preventDuplicateMiner = constants.getCONTINUOUS_MINING_LIMIT();
            Block parent = blockStore.getBlockByHash(block.getParentHash());
            for (int i = 0; i < preventDuplicateMiner && parent != null; i++) {
                if (FastByteComparisons.equal(block.getCoinbase(), parent.getCoinbase())) {
                    logger.warn("A block with the same coinbase({}) as a contiguous ancestor({}) can not be linked to a chain.", AddressUtil.getShortAddress(block.getCoinbase()), parent.getShortDescr());
                    repo.rollback();
                    summary = null;
                }
                parent = blockStore.getBlockByHash(parent.getParentHash());
            }
        }


        // 블록에 등록된 마스터노드가 저장소에도 마스터노드로 등록되어있어야 한다.
        if(constants.isMasternodeRewardBlock(block.getNumber())) {
            List<byte[]> mnList = new ArrayList<>(block.getMnGeneralList());
            mnList.addAll(block.getMnMajorList());
            mnList.addAll(block.getMnPrivateList());

            for(byte[] mn : mnList) {
                if(repo.getMnStartBalance(mn).compareTo(BigInteger.ZERO) == 0) {
                    logger.warn("The masternode({}) contained in the block can not be found in the repository.", ByteUtil.toHexString(mn));
                    ConsoleUtil.printlnRed("The masternode(%s) contained in the block can not be found in the repository.", ByteUtil.toHexString(mn));

                    repo.rollback();
                    summary = null;
                }
            }
        }




        if (!FastByteComparisons.equal(block.getStateRoot(), repo.getRoot())) {
            ConsoleUtil.printlnRed(block.toString());
            ConsoleUtil.printlnRed(Hex.toHexString(block.getEncodedBody()));

            stateLogger.warn("BLOCK: State conflict or received invalid block. block: {} worldstate {} mismatch BlockStateRoot {}", block.getNumber(), Hex.toHexString(repo.getRoot()), Hex.toHexString(block.getStateRoot()));
            stateLogger.warn("Conflict block dump: {}", Hex.toHexString(block.getEncoded()));

//            track.rollback();
//            repository.rollback();
            repository = repository.getSnapshotTo(origRoot);

            // block is bad so 'rollback' the state root to the original state
//            ((RepositoryImpl) repository).setRoot(origRoot);

//            track.rollback();
            // block is bad so 'rollback' the state root to the original state
//            ((RepositoryImpl) repository).setRoot(origRoot);

            if (config.exitOnBlockConflict()) {
                adminInfo.lostConsensus();
                System.out.println("CONFLICT: BLOCK #" + block.getNumber() + ", dump: " + Hex.toHexString(block.getEncoded()));
                System.exit(1);
            } else {
                summary = null;
            }
        }


        // 너무 오래전의 블록이 추가된 경우 오류로..
        /*if(block.getNumber() < getBestBlock().getNumber() - 3) {*/
        if (block.getNumber() < getBestBlock().getNumber()) {
            logger.warn("A block was created that should have been made long ago : {} < Best block {}", block.getNumber(), getBestBlock().getNumber());
            repo.rollback();
            summary = null;
        }


        if (summary != null) {
            repo.commit();
            updateTotalRewardPoint(block);
            summary.setTotalRewardPoint(block.getCumulativeRewardPoint());

            if (!byTest) {
                dbFlushManager.commit(() -> {
                    storeBlock(block, receipts);
                    repository.commit();
                });
            } else {
                storeBlock(block, receipts);
            }
        }

        return summary;
    }

    @Override
    public void flush() {
//        repository.flush();
//        stateDataSource.flush();
//        blockStore.flush();
//        transactionStore.flush();
//
//        repository = repository.getSnapshotTo(repository.getRoot());
//
//        if (isMemoryBoundFlush()) {
//            System.gc();
//        }
    }

    private boolean needFlushByMemory(double maxMemoryPercents) {
        return getRuntime().freeMemory() < (getRuntime().totalMemory() * (1 - maxMemoryPercents));
    }

    public static byte[] calcReceiptsTrie(List<TransactionReceipt> receipts) {
        Trie receiptsTrie = new TrieImpl();

        if (receipts == null || receipts.isEmpty())
            return HashUtil.EMPTY_TRIE_HASH;

        for (int i = 0; i < receipts.size(); i++) {
            receiptsTrie.put(RLP.encodeInt(i), receipts.get(i).getReceiptTrieEncoded());
        }
        return receiptsTrie.getRootHash();
    }

    private byte[] calcLogBloom(List<TransactionReceipt> receipts) {

        Bloom retBloomFilter = new Bloom();

        if (receipts == null || receipts.isEmpty())
            return retBloomFilter.getData();

        for (TransactionReceipt receipt : receipts) {
            retBloomFilter.or(receipt.getBloomFilter());
        }

        return retBloomFilter.getData();
    }



    public Block getParent(BlockHeader header) {

        return blockStore.getBlockByHash(header.getParentHash());
    }


    public boolean isValid(BlockHeader header) {
        if (parentHeaderValidator == null) return true;

        Block parentBlock = getParent(header);

        if (!parentHeaderValidator.validate(header, parentBlock.getHeader())) {

            if (logger.isErrorEnabled())
                parentHeaderValidator.logErrors(logger);

            return false;
        }

        return true;
    }

    /**
     * This mechanism enforces a homeostasis in terms of the time between blocks;
     * a smaller period between the last two blocks results in an increase in the
     * difficulty level and thus additional computation required, lengthening the
     * likely next period. Conversely, if the period is too large, the difficulty,
     * and expected time to the next block, is reduced.
     */
    private boolean isValid(Repository repo, Block block) {

        boolean isValid = true;

        if (!block.isGenesis()) {
            isValid = isValid(block.getHeader());

            // Sanity checks
            String trieHash = Hex.toHexString(block.getTxTrieRoot());
            String trieListHash = Hex.toHexString(calcTxTrie(block.getTransactionsList()));


            if (!trieHash.equals(trieListHash)) {
                logger.warn("Block's given Trie Hash doesn't match: {} != {}", trieHash, trieListHash);
                return false;
            }

//            if (!validateUncles(block)) return false;

            List<Transaction> txs = block.getTransactionsList();
            if (!txs.isEmpty()) {
//                Repository parentRepo = repository;
//                if (!Arrays.equals(bestBlock.getHash(), block.getParentHash())) {
//                    parentRepo = repository.getSnapshotTo(getBlockByHash(block.getParentHash()).getStateRoot());
//                }

                Map<ByteArrayWrapper, BigInteger> curNonce = new HashMap<>();

                for (Transaction tx : txs) {
                    byte[] txSender = tx.getSender();
                    ByteArrayWrapper key = new ByteArrayWrapper(txSender);
                    BigInteger expectedNonce = curNonce.get(key);
                    if (expectedNonce == null) {
                        expectedNonce = repo.getNonce(txSender);
                    }
                    curNonce.put(key, expectedNonce.add(ONE));
                    BigInteger txNonce = new BigInteger(1, tx.getNonce());
                    if (!expectedNonce.equals(txNonce)) {
                        logger.warn("Invalid transaction: Tx nonce {} != expected nonce {} (parent nonce: {}): {}",
                                txNonce, expectedNonce, repo.getNonce(txSender), tx);
                        logger.warn("StateRoot {}, ParentBlock {}", Hex.toHexString(repo.getRoot()), Hex.toHexString(blockStore.getBlockByHash(block.getParentHash()).getStateRoot()));
                        return false;
                    }
                }
            }
        }

        return isValid;
    }

    private boolean isValidMasterNodeTx(Repository repo, Transaction tx) {
        if(tx.getReceiveAddress() == null) {
            return false;
        }

        // 송금액이 0이어야 한다.
        if(!ByteUtil.bytesToBigInteger(tx.getValue()).equals(BigInteger.ZERO)) {
            return false;
        }

        // 송금인과 수신인이 동일해야 한다.
        if(!FastByteComparisons.equal(tx.getSender(), tx.getReceiveAddress())) {
            return false;
        }

        // data에 포함된 내용이 주소 형식에 맞아야 한다.
        byte[] recipient = repo.getMnRecipient(tx.getSender());
        if(recipient == null || FastByteComparisons.equal(recipient, HashUtil.EMPTY_DATA_HASH)) {
            if(!Utils.isValidAddress(tx.getData())) {
                return false;
            }
        } else {
            if(tx.getData() != null && !Utils.isValidAddress(tx.getData())) {
                return false;
            }
        }


        /*
         * 마스터노드를 신청하는 주소의 잔고를 확인한다.
         * 전에 마스터노드에 참여했던 이력이 존재하는 경우, masternodeStartingBalance 값에 담보금액이 설정되어있다.
         * 이 경우에는, 신청자의 잔고가 담보금보다 작은 경우에는 더 진행시키지 않는다.
         * 담보금액이 설정되지 않은 경우에는, 신청자의 잔고가 정확한 담보 금액과 일치하는지 확인하도록 한다.
         */
        BigInteger senderBalance = repo.getBalance(tx.getSender());
        BigInteger masternodeStartingBalance = repo.getMnStartBalance(tx.getSender());


        // 신청자의 잔고가 정확히 담보금액과 같아야만 true
        if(masternodeStartingBalance.compareTo(BigInteger.ZERO) == 0) {
            return config.getBlockchainConfig().getCommonConstants().getMASTERNODE_LIMIT(senderBalance) > 0;
        }
        // 업데이터의 잔고가 참여 시 담보금액 이상이어야만 true
        else {
            return senderBalance.compareTo(masternodeStartingBalance) >= 0;
        }
    }

    private boolean isValidAddressMaskTx(TransactionReceipt receipt) {
        if(receipt == null) { return false; }

        Transaction tx = receipt.getTransaction();
        if(tx == null || tx.getReceiveAddress() == null) { return false; }

        if(!FastByteComparisons.equal(config.getBlockchainConfig().getCommonConstants().getADDRESS_MASKING_ADDRESS(), tx.getReceiveAddress())) {return false;}

        if(!receipt.isSuccessful()) { return false;}

        // MaskAddtion, MaskHandOver 이벤트를 포함해야 한다.
        CallTransaction.Contract contract = new CallTransaction.Contract(ContractLoader.readABI(ContractLoader.CONTRACT_ADDRESS_MASKING));
        List<LogInfo> events = receipt.getLogInfoList();
        for(LogInfo event : events) {
            String eventName = contract.parseEvent(event).function.name;
            if(eventName.equals("MaskAddition") || eventName.equals("MaskHandOver")) {
                return true;
            }
        }

        return false;
    }

    private boolean isValidProofOfKnowledgeTx(TransactionReceipt receipt) {
        if(receipt == null) { return false; }

        Transaction tx = receipt.getTransaction();
        if(tx == null || tx.getReceiveAddress() == null) { return false; }

        if(!FastByteComparisons.equal(config.getBlockchainConfig().getCommonConstants().getPROOF_OF_KNOWLEDGE(), tx.getReceiveAddress())) {return false;}

        if(!receipt.isSuccessful()) { return false;}

        // RegisterProofKey, RemoveProofKey 이벤트를 포함해야 한다.
        CallTransaction.Contract contract = new CallTransaction.Contract(ContractLoader.readABI(ContractLoader.CONTRACT_PROOF_OF_KNOWLEDGE));
        List<LogInfo> events = receipt.getLogInfoList();
        for(LogInfo event : events) {
            String eventName = contract.parseEvent(event).function.name;
            if(eventName.equals("RegisterProofKey") || eventName.equals("RemoveProofKey")) {
                return true;
            }
        }

        return false;
    }


    /**
     * 검증하는 트랜잭션의 receipt 내용이 미네랄을 구입하는 것인지 확인한다.
     *
     * @param receipt txReceipt
     * @return true : MNR event included
     */
    private boolean isValidBuyMineralTx(TransactionReceipt receipt) {
        if(receipt == null) { return false; }

        Transaction tx = receipt.getTransaction();
        if(tx == null || tx.getReceiveAddress() == null) { return false; }

        if(!FastByteComparisons.equal(config.getBlockchainConfig().getCommonConstants().getBUY_MINERAL(), tx.getReceiveAddress())) {return false;}

        if(!receipt.isSuccessful()) { return false;}

        // MNR 이벤트를 포함해야 한다.
        CallTransaction.Contract contract = new CallTransaction.Contract(ContractLoader.readABI(ContractLoader.CONTRACT_BUY_MINERAL));
        List<LogInfo> events = receipt.getLogInfoList();
        for(LogInfo event : events) {
            String eventName = contract.parseEvent(event).function.name;
            if(eventName.equals("MNR")) {
                return true;
            }
        }

        return false;
    }


    private boolean isValidMasternodeEarlyBird(TransactionReceipt receipt) {
        if(receipt == null) { return false; }

        Transaction tx = receipt.getTransaction();
        if(tx == null || tx.getReceiveAddress() == null) { return false; }

        if(!FastByteComparisons.equal(config.getBlockchainConfig().getCommonConstants().getMASTERNODE_PLATFORM_CONTRACT(), tx.getReceiveAddress())) {return false;}

        if(!receipt.isSuccessful()) { return false;}

        // 블록 번호 검증은 스마트컨트렉트에서 수행한다

        // EarlyBirdRegister 또는 MasternodeCancel 이벤트를 포함해야한다.
        CallTransaction.Contract contract = new CallTransaction.Contract(ContractLoader.readABI(ContractLoader.CONTRACT_MASTERNODE_PLATFORM));
        List<LogInfo> events = receipt.getLogInfoList();
        for(LogInfo event : events) {
            String eventName = contract.parseEvent(event).function.name;
            if(eventName.equals("EarlyBirdRegister") || eventName.equals("MasternodeCancel")) {
                return true;
            }
        }

        return false;
    }






    private BlockSummary processBlock(Repository track, Block block) {

        if (!block.isGenesis() && !config.blockChainOnly()) {
            return applyBlock(track, block);
        }
        else {
            return new BlockSummary(block, new HashMap<>(), new ArrayList<>(), new ArrayList<>());
        }
    }

    private BlockSummary applyBlock(Repository track, Block block) {

        logger.debug("applyBlock: block: [{}] tx.list: [{}]", block.getNumber(), block.getTransactionsList().size());

        long saveTime = System.nanoTime();
        int txIndex = 1;
        long totalGasUsed = 0;
        BigInteger totalMineralUsed = BigInteger.ZERO;
        List<TransactionReceipt> receipts = new ArrayList<>();
        List<TransactionExecutionSummary> summaries = new ArrayList<>();

        // 마스터노드 목록 중에 유효기간이 지난 노드를 정리한다.
        track.cleaningMasterNodes(block.getNumber());

        for (Transaction tx : block.getTransactionsList()) {
            stateLogger.debug("apply block: [{}] tx: [{}] nonce: [{}] ", block.getNumber(), txIndex, ByteUtil.bytesToBigInteger(tx.getNonce()));

            Repository txTrack = track.startTracking();
            TransactionExecutor executor = new TransactionExecutor(
                    tx,
                    block.getCoinbase(),
                    txTrack,
                    blockStore,
                    programInvokeFactory,
                    block,
                    listener,
                    totalGasUsed,
                    totalMineralUsed)
                    .withCommonConfig(commonConfig);

            executor.init();
            executor.execute();
            executor.go();
            TransactionExecutionSummary summary = executor.finalization();

            totalGasUsed += executor.getGasUsed();
            totalMineralUsed = totalMineralUsed.add(executor.getMineralUsed());


            if (summary != null) {
                // 마스터노드 상태를 업데이트하는 tx일 경우
                if (isValidMasterNodeTx(txTrack, tx)) {
                    txTrack.updateMasterNode(tx, block.getNumber());
                }
                // AddressMasking 관련 tx인 경우
                else if (isValidAddressMaskTx(executor.getReceipt())) {
                    txTrack.updateAddressMask(executor.getReceipt());
                }
                // ProofOfKnowledge 관련 tx인 경우
                else if (isValidProofOfKnowledgeTx(executor.getReceipt())) {
                    txTrack.updateProofOfKnowledge(executor.getReceipt());
                } else if (isValidBuyMineralTx(executor.getReceipt())) {
                    txTrack.updatePurchasedMineral(executor.getReceipt(), block.getNumber());
                }
                // Check earlyBird-Masternode
                else if (isValidMasternodeEarlyBird(executor.getReceipt())) {
                    txTrack.updateMasterNodeEarlyBird(executor.getReceipt(), block.getNumber());
                }
                // 마스터노드 잔고가 참여시 잔고보다 낮아지면 해제시킨다
                else {
                    txTrack.checkMasternodeCollateral(tx.getSender(), block.getNumber());
                }
            }

            txTrack.commit();
            final TransactionReceipt receipt = executor.getReceipt();

            receipt.setTxStatus(receipt.isSuccessful());


            stateLogger.info("block: [{}] executed tx: [{}] \n  state: [{}]", block.getNumber(), txIndex, Hex.toHexString(track.getRoot()));

            stateLogger.info("[{}] ", receipt.toString());

            if (stateLogger.isInfoEnabled()) {
                stateLogger.info("tx[{}].receipt: [{}] ", txIndex, Hex.toHexString(receipt.getEncoded()));
            }

            txIndex++;

            receipts.add(receipt);
            if (summary != null) {
                summaries.add(summary);
            }
        }


        Map<byte[], BigInteger> rewards = addReward(track, block, summaries);

        stateLogger.info("applied reward for block: [{}]  \n  state: [{}]",
                block.getNumber(),
                Hex.toHexString(track.getRoot()));

        long totalTime = System.nanoTime() - saveTime;
        adminInfo.addBlockExecTime(totalTime);
        logger.debug("block: num: [{}] hash: [{}], executed after: [{}]nano", block.getNumber(), block.getShortHash(), totalTime);

        return new BlockSummary(block, rewards, receipts, summaries);
    }

    /**
     * Add reward to block- and every uncle coinbase
     * assuming the entire block is valid.
     *
     * @param block object containing the header and uncles
     */
    private Map<byte[], BigInteger> addReward(Repository track, Block block, List<TransactionExecutionSummary> summaries) {

        Map<byte[], BigInteger> rewards = new HashMap<>();
        Constants constants = config.getBlockchainConfig().getConfigForBlock(block.getNumber()).getConstants();

        // 마스터노드 보상을 분배한다.
        if(constants.isMasternodeRewardBlock(block.getNumber())) {

            BigInteger storedMnReward = track.getBalance(constants.getMASTERNODE_STORAGE());
            BigInteger mnBasicReward = block.getMnReward();

            if(mnBasicReward.compareTo(BigInteger.ZERO) > 0) {
                // 블록에 저장된 마스터노드를 구한다.
                List<byte[]> mnGeneralNormalOnBLock = block.getMnGeneralList();
                List<byte[]> mnGeneralLateOnBLock   = block.getMnGeneralLateList();
                List<byte[]> mnMajorNormalOnBlock   = block.getMnMajorList();
                List<byte[]> mnMajorLateOnBlock     = block.getMnMajorLateList();
                List<byte[]> mnPrivateNormalOnBlock = block.getMnPrivateList();
                List<byte[]> mnPrivateLateOnBlock   = block.getMnPrivateLateList();

                // 레포지토리에 저장된 마스터노드 갯수를 구한다.
                List<byte[]> generalEarly   = track.getMasterNodeList(constants.getMASTERNODE_GENERAL_BASE_EARLY_RUN());
                List<byte[]> generalNormal  = track.getMasterNodeList(constants.getMASTERNODE_GENERAL_BASE_NORMAL());
                List<byte[]> generalLate    = track.getMasterNodeList(constants.getMASTERNODE_GENERAL_BASE_LATE(), block.getNumber());
                List<byte[]> majorEarly     = track.getMasterNodeList(constants.getMASTERNODE_MAJOR_BASE_EARLY_RUN());
                List<byte[]> majorNormal    = track.getMasterNodeList(constants.getMASTERNODE_MAJOR_BASE_NORMAL());
                List<byte[]> majorLate      = track.getMasterNodeList(constants.getMASTERNODE_MAJOR_BASE_LATE(), block.getNumber());
                List<byte[]> privateEarly   = track.getMasterNodeList(constants.getMASTERNODE_PRIVATE_BASE_EARLY_RUN());
                List<byte[]> privateNormal  = track.getMasterNodeList(constants.getMASTERNODE_PRIVATE_BASE_NORMAL());
                List<byte[]> privateLate    = track.getMasterNodeList(constants.getMASTERNODE_PRIVATE_BASE_LATE(), block.getNumber());

                byte[] repoMnHash = calcMnHash(generalEarly, generalNormal, generalLate, majorEarly, majorNormal, majorLate, privateEarly, privateNormal, privateLate);

                int countGN = block.getMnGeneralList().size();
                int countGL = block.getMnGeneralLateList().size();
                int countMN = block.getMnMajorList().size();
                int countML = block.getMnMajorLateList().size();
                int countPN = block.getMnPrivateList().size();
                int countPL = block.getMnPrivateLateList().size();
                MasternodeRewardData masternodeRewardData = MasternodeRewardUtil.calcRewards(constants, block.getMnReward(), countGN, countGL, countMN, countML, countPN, countPL);

                if(storedMnReward.compareTo(masternodeRewardData.getTotal()) >= 0 && FastByteComparisons.equal(block.getMnHash(), repoMnHash)) {
                    // 재단에 할당된 부분을 전송한다.
                    distributeReward(constants.getMASTERNODE_STORAGE(), constants.getFOUNDATION_STORAGE(), masternodeRewardData.getFoundation(), track, rewards, block.getNumber());

                    // Normal distribute
                    distributeMnReward(masternodeRewardData.getGeneralNormal(), mnGeneralNormalOnBLock, track, constants, rewards, block.getNumber());
                    distributeMnReward(masternodeRewardData.getMajorNormal(),   mnMajorNormalOnBlock, track, constants, rewards, block.getNumber());
                    distributeMnReward(masternodeRewardData.getPrivateNormal(), mnPrivateNormalOnBlock, track, constants, rewards, block.getNumber());

                    // Late distribute
                    distributeMnReward(masternodeRewardData.getGeneralLate(),   mnGeneralLateOnBLock, track, constants, rewards, block.getNumber());
                    distributeMnReward(masternodeRewardData.getMajorLate(),     mnMajorLateOnBlock, track, constants, rewards, block.getNumber());
                    distributeMnReward(masternodeRewardData.getPrivateLate(),   mnPrivateLateOnBlock, track, constants, rewards, block.getNumber());
                } else {
                    ConsoleUtil.printlnRed("Size of masterNode on Block and on Repository is DIFFERENT!!");
                    logger.error("Size of masterNode on Block and on Repository is DIFFERENT!!");
                }
            }
        }


        BigInteger blockReward = constants.getBLOCK_REWARD(block.getNumber());
        BigInteger totalFees = BigInteger.ZERO;

        // 트랜잭션 수수료 보상에서 미네랄로 사용된 부분은 제외한다.
        for (TransactionExecutionSummary summary : summaries) {
            totalFees = totalFees.add(summary.getFee()).subtract(summary.getMineralUsed());
        }

        // 블록 보상의 45%는 채굴자에게, 45%는 마스터 노드에게, 10%는 APIS 재단으로 배분해야한다

        BigInteger totalReward = blockReward.add(totalFees);
        BigInteger minerReward = totalReward .multiply(constants.getREWARD_PORTION_MINER()).divide(constants.getREWARD_PORTION_DENOMINATOR());
        BigInteger masternodesReward = totalReward .multiply(constants.getREWARD_PORTION_MASTERNODES()).divide(constants.getREWARD_PORTION_DENOMINATOR());
        BigInteger managementReward = totalReward .subtract(minerReward).subtract(masternodesReward);

        byte[] addressMasterNode = constants.getMASTERNODE_STORAGE();
        byte[] addressManagement = constants.getFOUNDATION_STORAGE();


        rewards.put(block.getCoinbase(), minerReward);
        rewards.put(addressMasterNode, masternodesReward);
        rewards.put(addressManagement, managementReward);

        track.addBalance(block.getCoinbase(), minerReward);
        track.addBalance(addressMasterNode, masternodesReward);
        track.addBalance(addressManagement, managementReward);

        track.addReward(block.getCoinbase(), minerReward);


        return rewards;
    }


    private void distributeMnReward(BigInteger wholeReward, List<byte[]> listNode, Repository track, Constants constants, Map<byte[], BigInteger> rewards, long blockNumber) {
        for(byte[] mn : listNode) {
            byte[] recipient = track.getMnRecipient(mn);
            if(recipient == null) {
                recipient = mn;
            }

            distributeReward(constants.getMASTERNODE_STORAGE(), recipient, wholeReward, track, rewards, blockNumber);
            // ConsoleUtil.printlnPurple("Normal     - [%s]>>[%s] : %s", ByteUtil.toHexString(mn), ByteUtil.toHexString(recipient), ApisUtil.readableApis(wholeReward, ',', true));
        }
    }



    private void distributeReward(byte[] rewardFrom, byte[] rewardTo, BigInteger rewardAmount, Repository track, Map<byte[], BigInteger> rewards, long blockNumber) {
        if(blockNumber > config.getBlockchainConfig().getConfigForBlock(blockNumber).getConstants().getINIT_MINERAL_APPLY_BLOCK()) {
            BIUtil.transfer(track, rewardFrom, rewardTo, rewardAmount, blockNumber);
        } else {
            BIUtil.transfer(track, rewardFrom, rewardTo, rewardAmount);
        }
        track.addReward(rewardTo, rewardAmount);
        rewards.put(rewardTo, rewardAmount);
    }


    @Override
    public synchronized void storeBlock(Block block, List<TransactionReceipt> receipts) {

        if (fork)
            blockStore.saveBlock(block, totalRewardPoint, false);
        else
            blockStore.saveBlock(block, totalRewardPoint, true);

        for (int i = 0; i < receipts.size(); i++) {
            transactionStore.put(new TransactionInfo(receipts.get(i), block.getHash(), i));
        }

        if (pruneManager != null) {
            pruneManager.blockCommitted(block.getHeader());
        }


        logger.debug("Block saved: number: {}, hash: {}, RP: {}", block.getNumber(), block.getShortHash(), totalRewardPoint);

        /* 기존 소스코드에서는 1번째 구문으로 BestBlock을 설정했었음 (마지막에 저장되는 블럭이 베스트)
         * 그러나 blockStore.saveBlock 에서 RP 값을 비교해서 BestBlock을 선정하기 때문에  2번째 줄과 같이 변경했다.
         */
        setBestBlock(block);
        //setBestBlock(blockStore.getBestBlock());

        if (logger.isDebugEnabled())
            logger.debug("block added to the blockChain: index: [{}]", block.getNumber());
        if (block.getNumber() % 100 == 0)
            logger.info("*** Last block added [ #{} ]", block.getNumber());

    }


    public boolean hasParentOnTheChain(Block block) {
        return getParent(block.getHeader()) != null;
    }

    @Override
    public List<Chain> getAltChains() {
        return altChains;
    }

    @Override
    public List<Block> getGarbage() {
        return garbage;
    }

    @Override
    public TransactionStore getTransactionStore() {
        return transactionStore;
    }

    @Override
    public void setBestBlock(Block block) {
        bestBlock = block;
        repository = repository.getSnapshotTo(block.getStateRoot());
    }

    @Override
    public synchronized Block getBestBlock() {
        // the method is synchronized since the bestBlock might be
        // temporarily switched to the fork while importing non-best block
        return bestBlock;
    }

    @Override
    public synchronized void close() {
        blockStore.close();
    }

    @Override
    public BigInteger getTotalRewardPoint() {
        return totalRewardPoint;
    }

    @Override
    public synchronized void updateTotalRewardPoint(Block block) {
        //totalRewardPoint = totalRewardPoint.add(block.getRewardPointBI());
        totalRewardPoint = block.getCumulativeRewardPoint();

        logger.debug("Reward Point: updated to {}", totalRewardPoint);
    }

    @Override
    public void setTotalRewardPoint (BigInteger totalRewardPoint) {
        this.totalRewardPoint= totalRewardPoint;
    }

    /**
     * 블록들을 파일에 기록한다.
     * @param block 대상 블록
     */
    private void recordBlock(Block block) {

        if (!config.recordBlocks()) return;

        String dumpDir = config.databaseDir() + "/" + config.dumpDir();

        File dumpFile = new File(dumpDir + "/blocks-rec.dmp");
        FileWriter fw = null;
        BufferedWriter bw = null;

        try {
            dumpFile.getParentFile().mkdirs();
            if (!dumpFile.exists()) {
                dumpFile.createNewFile();
            }

            fw = new FileWriter(dumpFile.getAbsoluteFile(), true);
            bw = new BufferedWriter(fw);

            if (bestBlock.isGenesis()) {
                bw.write(Hex.toHexString(bestBlock.getEncoded()));
                bw.write("\n\n");
                bw.write(bestBlock.toString());
            }

            //bw.write(Hex.toHexString(block.getEncoded()));
            bw.write("\n\n");
            bw.write(block.toString());

        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        } finally {
            try {
                if (bw != null) bw.close();
                if (fw != null) fw.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public void updateBlockTotDifficulties(long startFrom) {
        // no synchronization here not to lock instance for long period
        while(true) {
            synchronized (this) {
                ((IndexedBlockStore) blockStore).updateTotalRewardPoints(startFrom);
                if (startFrom == bestBlock.getNumber()) {
                    totalRewardPoint = blockStore.getTotalRewardPointForHash(bestBlock.getHash());
                    break;
                }
                startFrom++;
            }
        }
    }

    public void setRepository(Repository repository) {
        this.repository = repository;
    }

    public void setProgramInvokeFactory(ProgramInvokeFactory factory) {
        this.programInvokeFactory = factory;
    }

    public void setExitOn(long exitOn) {
        this.exitOn = exitOn;
    }

    public void setMinerCoinbase(byte[] minerCoinbase) {
        this.minerCoinbase = minerCoinbase;
    }

    @Override
    public byte[] getMinerCoinbase() {
        return minerCoinbase;
    }

    public void setMinerExtraData(byte[] minerExtraData) {
        this.minerExtraData = minerExtraData;
    }

    public boolean isBlockExist(byte[] hash) {
        return blockStore.isBlockExist(hash);
    }

    public void setParentHeaderValidator(DependentBlockHeaderRule parentHeaderValidator) {
        this.parentHeaderValidator = parentHeaderValidator;
    }

    public void setPendingState(PendingState pendingState) {
        this.pendingState = pendingState;
    }

    public PendingState getPendingState() {
        return pendingState;
    }

    @Override
    public List<BlockHeader> getListOfHeadersStartFrom(BlockIdentifier identifier, int skip, int limit, boolean reverse) {
        List<BlockHeader> headers = new ArrayList<>();
        Iterator<BlockHeader> iterator = getIteratorOfHeadersStartFrom(identifier, skip, limit, reverse);
        while (iterator.hasNext()) {
            headers.add(iterator.next());
        }

        return headers;
    }

    @Override
    public Iterator<BlockHeader> getIteratorOfHeadersStartFrom(BlockIdentifier identifier, int skip, int limit, boolean reverse) {

        // Identifying block header we'll move from
        BlockHeader startHeader;
        if (identifier.getHash() != null) {
            startHeader = findHeaderByHash(identifier.getHash());
        } else {
            startHeader = findHeaderByNumber(identifier.getNumber());
        }

        // If nothing found or provided hash is not on main chain, return empty array
        if (startHeader == null) {
            return EmptyBlockHeadersIterator.INSTANCE;
        }

        if (identifier.getHash() != null) {
            BlockHeader mainChainHeader = findHeaderByNumber(startHeader.getNumber());
            if (!startHeader.equals(mainChainHeader)) return EmptyBlockHeadersIterator.INSTANCE;
        }

        return new BlockHeadersIterator(startHeader, skip, limit, reverse);
    }

    /**
     * Searches block in blockStore, if it's not found there
     * and headerStore is defined, searches blockHeader in it.
     * @param number block number
     * @return  Block header
     */
    private BlockHeader findHeaderByNumber(long number) {
        Block block = blockStore.getChainBlockByNumber(number);
        if (block == null) {
            if (headerStore != null) {
                return headerStore.getHeaderByNumber(number);
            } else {
                return null;
            }
        } else {
            return block.getHeader();
        }
    }

    /**
     * Searches block in blockStore, if it's not found there
     * and headerStore is defined, searches blockHeader in it.
     * @param hash block hash
     * @return Block header
     */
    private BlockHeader findHeaderByHash(byte[] hash) {
        Block block = blockStore.getBlockByHash(hash);
        if (block == null) {
            if (headerStore != null) {
                return headerStore.getHeaderByHash(hash);
            } else {
                return null;
            }
        } else {
            return block.getHeader();
        }
    }

    static class EmptyBlockHeadersIterator implements Iterator<BlockHeader> {
        final static EmptyBlockHeadersIterator INSTANCE = new EmptyBlockHeadersIterator();

        @Override
        public boolean hasNext() {
            return false;
        }

        @Override
        public BlockHeader next() {
            throw new NoSuchElementException("Nothing left");
        }
    }

    class BlockHeadersIterator implements Iterator<BlockHeader> {
        private final BlockHeader startHeader;
        private final int skip;
        private final int limit;
        private final boolean reverse;
        private Integer position = 0;
        private Pair<Integer, BlockHeader> cachedNext = null;

        BlockHeadersIterator(BlockHeader startHeader, int skip, int limit, boolean reverse) {
            this.startHeader = startHeader;
            this.skip = skip;
            this.limit = limit;
            this.reverse = reverse;
        }

        @Override
        public boolean hasNext() {
            if (startHeader == null || position >= limit) {
                return false;
            }

            if (position == 0) {
                // First
                cachedNext = Pair.of(0, startHeader);
                return true;
            } else if (cachedNext.getLeft().equals(position)) {
                // Already cached
                return true;
            } else {
                // Main logic
                BlockHeader prevHeader = cachedNext.getRight();
                long nextBlockNumber;
                if (reverse) {
                    nextBlockNumber = prevHeader.getNumber() - 1 - skip;
                } else {
                    nextBlockNumber = prevHeader.getNumber() + 1 + skip;
                }

                BlockHeader nextHeader = null;
                if (nextBlockNumber >= 0 && nextBlockNumber <= blockStore.getBestBlock().getNumber()) {
                    nextHeader = findHeaderByNumber(nextBlockNumber);
                }

                if (nextHeader == null) {
                    return false;
                } else {
                    cachedNext = Pair.of(position, nextHeader);
                    return true;
                }
            }
        }

        @Override
        public BlockHeader next() {
            if (!hasNext()) {
                throw new NoSuchElementException("Nothing left");
            }

            if (cachedNext == null || !cachedNext.getLeft().equals(position)) {
                throw new ConcurrentModificationException("Concurrent modification");
            }
            ++position;

            return cachedNext.getRight();
        }
    }


    @Override
    public List<byte[]> getListOfBodiesByHashes(List<byte[]> hashes) {
        List<byte[]> bodies = new ArrayList<>(hashes.size());

        for (byte[] hash : hashes) {
            Block block = blockStore.getBlockByHash(hash);
            if (block == null) break;
            bodies.add(block.getEncodedBody());
        }

        return bodies;
    }

    @Override
    public Iterator<byte[]> getIteratorOfBodiesByHashes(List<byte[]> hashes) {
        return new BlockBodiesIterator(hashes);
    }

    class BlockBodiesIterator implements Iterator<byte[]> {
        private final List<byte[]> hashes;
        private Integer position = 0;


        BlockBodiesIterator(List<byte[]> hashes) {
            this.hashes = new ArrayList<>(hashes);
        }

        @Override
        public boolean hasNext() {
            return position < hashes.size() && blockStore.getBlockByHash(hashes.get(position)) != null;
        }

        @Override
        public byte[] next() {
            if (!hasNext()) {
                throw new NoSuchElementException("Nothing left");
            }

            Block block = blockStore.getBlockByHash(hashes.get(position));
            if (block == null) {
                throw new NoSuchElementException("Nothing left");
            }
            ++position;

            return block.getEncodedBody();
        }
    }

    private class State {
        //        Repository savedRepo = repository;
        byte[] root = repository.getRoot();
        Block savedBest = bestBlock;
        BigInteger savedTotalRewardPoint = totalRewardPoint;
    }

    public void setPruneManager(PruneManager pruneManager) {
        this.pruneManager = pruneManager;
    }

    public void setHeaderStore(HeaderStore headerStore) {
        this.headerStore = headerStore;
    }
}
