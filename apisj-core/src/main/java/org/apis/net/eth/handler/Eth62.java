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
package org.apis.net.eth.handler;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.netty.channel.ChannelHandlerContext;
import org.apache.commons.lang3.tuple.Pair;
import org.apis.config.CommonConfig;
import org.apis.core.*;
import org.apis.crypto.HashUtil;
import org.apis.db.BlockStore;
import org.apis.db.ByteArrayWrapper;
import org.apis.mine.MinedBlockCache;
import org.apis.net.eth.EthVersion;
import org.apis.config.SystemProperties;
import org.apis.net.eth.message.*;
import org.apis.listener.CompositeEthereumListener;
import org.apis.net.message.ReasonCode;
import org.apis.net.rlpx.discover.NodeManager;
import org.apis.net.submit.*;
import org.apis.net.submit.TransactionExecutor;
import org.apis.sync.SyncManager;
import org.apis.sync.PeerState;
import org.apis.sync.SyncStatistics;
import org.apis.util.*;
import org.apis.validator.BlockHeaderRule;
import org.apis.validator.BlockHeaderValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import javax.annotation.Nullable;
import java.math.BigInteger;
import java.util.*;

import static java.lang.Math.min;
import static java.util.Collections.singletonList;
import static org.apis.net.message.ReasonCode.USELESS_PEER;
import static org.apis.datasource.MemSizeEstimator.ByteArrayEstimator;
import static org.apis.sync.PeerState.*;
import static org.apis.sync.PeerState.BLOCK_RETRIEVING;
import static org.apis.util.Utils.longToTimePeriod;
import static org.spongycastle.util.encoders.Hex.toHexString;

/**
 * Eth 62
 *
 * @author Mikhail Kalinin
 * @since 04.09.2015
 */
@Component("Eth62")
@Scope("prototype")
public class Eth62 extends EthHandler {

    protected static final int MAX_HASHES_TO_SEND = 1024;

    public static final int MAX_MESSAGE_SIZE = 32 * 1024 * 1024;

    protected final static Logger logger = LoggerFactory.getLogger("sync");
    protected final static Logger loggerNet = LoggerFactory.getLogger("net");

    @Autowired
    protected BlockStore blockstore;

    @Autowired
    protected SyncManager syncManager;

    @Autowired
    protected PendingState pendingState;

    @Autowired
    protected NodeManager nodeManager;

    protected EthState ethState = EthState.INIT;

    protected PeerState peerState = IDLE;
    protected boolean syncDone = false;

    /**
     * Number and hash of best known remote block
     */
    protected BlockIdentifier bestKnownBlock;
    private BigInteger totalRewardPoint;

    private RewardPoint mRewardPoint;

    ChannelHandlerContext ctx;

    private final HashMap <ByteArrayWrapper, Long> receivedBlocks = new HashMap<>();

    /**
     * Header list sent in GET_BLOCK_BODIES message,
     * used to create blocks from headers and bodies
     * also, is useful when returned BLOCK_BODIES msg doesn't cover all sent hashes
     * or in case when peer is disconnected
     */
    protected final List<BlockHeaderWrapper> sentHeaders = Collections.synchronizedList(new ArrayList<BlockHeaderWrapper>());
    protected SettableFuture<List<Block>> futureBlocks;

    protected final SyncStatistics syncStats = new SyncStatistics();

    protected GetBlockHeadersMessageWrapper headerRequest;

    private Map<Long, BlockHeaderValidator> validatorMap;
    protected long lastReqSentTime;
    protected long connectedTime = System.currentTimeMillis();
    protected long processingTime = 0;



    private static final EthVersion version = EthVersion.V62;

    public Eth62() {
        this(version);
    }

    Eth62(final EthVersion version) {
        super(version);
    }

    @Autowired
    public Eth62(final SystemProperties config, final Blockchain blockchain,
                 final BlockStore blockStore, final CompositeEthereumListener ethereumListener) {
        this(version, config, blockchain, blockStore, ethereumListener);
    }

    Eth62(final EthVersion version, final SystemProperties config,
          final Blockchain blockchain, final BlockStore blockStore,
          final CompositeEthereumListener ethereumListener) {
        super(version, config, blockchain, blockStore, ethereumListener);
    }

    @Override
    public void channelRead0(final ChannelHandlerContext ctx, EthMessage msg) throws InterruptedException {
        super.channelRead0(ctx, msg);
        this.ctx = ctx;

        switch (msg.getCommand()) {
            case STATUS:
                processStatus((StatusMessage) msg, ctx);
                break;
            case NEW_BLOCK_HASHES:
                processNewBlockHashes((NewBlockHashesMessage) msg);
                break;
            case TRANSACTIONS:
                processTransactions((TransactionsMessage) msg);
                break;
            case GET_BLOCK_HEADERS:
                processGetBlockHeaders((GetBlockHeadersMessage) msg);
                break;
            case BLOCK_HEADERS:
                processBlockHeaders((BlockHeadersMessage) msg);
                break;
            case GET_BLOCK_BODIES:
                processGetBlockBodies((GetBlockBodiesMessage) msg);
                break;
            case BLOCK_BODIES:
                processBlockBodies((BlockBodiesMessage) msg);
                break;
            case NEW_BLOCK:
                processNewBlock((NewBlockMessage) msg);
                break;
            case MINED_BLOCK_LIST:
                processMinedBlocks((MinedBlockMessage) msg);
                break;
            default:
                break;
        }
    }

    /*************************
     *    Message Sending    *
     *************************/

    @Override
    public synchronized void sendStatus() {
        byte protocolVersion = getVersion().getCode();
        int networkId = config.networkId();
        byte[] coinbase = config.getMinerCoinbase();

        final BigInteger totalRewardPoint;
        final byte[] bestHash;

        if (syncManager.isFastSyncRunning()) {
            // while fastsync is not complete reporting block #0
            // until all blocks/receipts are downloaded
            bestHash = blockstore.getBlockHashByNumber(0);
            Block genesis = blockstore.getBlockByHash(bestHash);
            totalRewardPoint = genesis.getRewardPoint();
        } else {
            // Getting it from blockstore, not blocked by blockchain sync
            bestHash = blockstore.getBestBlock().getHash();
            //totalRewardPoint = blockchain.getTotalRewardPoint();
            totalRewardPoint = blockchain.getBestBlock().getCumulativeRewardPoint();
        }

        StatusMessage msg = new StatusMessage(protocolVersion, networkId, ByteUtil.bigIntegerToBytes(totalRewardPoint), bestHash, config.getGenesis().getHash(), coinbase);
        sendMessage(msg, true);

        ethState = EthState.STATUS_SENT;

        sendNextHeaderRequest();
    }

    @Override
    public synchronized void sendNewBlockHashes(Block block) {

        BlockIdentifier identifier = new BlockIdentifier(block.getHash(), block.getNumber());
        NewBlockHashesMessage msg = new NewBlockHashesMessage(singletonList(identifier));
        sendMessage(msg, true);
    }

    @Override
    public synchronized void sendTransaction(List<Transaction> txs) {
        TransactionsMessage msg = new TransactionsMessage(txs);
        sendMessage(msg);
    }

    @Override
    public void sendMinedBlocks(List<Block> minedBlocks) {
        List<Block> blocks = new ArrayList<>(minedBlocks);

        // 이미 상대방이 보유하고 있는 블록은 전송하지 않는다
        blocks.removeIf(block -> receivedBlocks.get(new ByteArrayWrapper(block.getHash())) != null);

        for(Block block : blocks) {
            receivedBlocks.put(new ByteArrayWrapper(block.getHash()), block.getNumber());
        }

        MinedBlockMessage msg = new MinedBlockMessage(blocks);
        //ctx.writeAndFlush(msg);
        sendMessage(msg, true);
    }


    @Override
    public synchronized ListenableFuture<List<BlockHeader>> sendGetBlockHeaders(long blockNumber, int maxBlocksAsk, boolean reverse) {

        if (ethState == EthState.STATUS_SUCCEEDED && (peerState != IDLE)) return null;

        if(logger.isTraceEnabled()) logger.trace(
                "Peer {}: queue GetBlockHeaders, blockNumber [{}], maxBlocksAsk [{}]",
                channel.getPeerIdShort(),
                blockNumber,
                maxBlocksAsk
        );

        if (headerRequest != null) {
            throw new RuntimeException("The peer is waiting for headers response: " + this);
        }

        GetBlockHeadersMessage headersRequest = new GetBlockHeadersMessage(blockNumber, null, maxBlocksAsk, 0, reverse);
        GetBlockHeadersMessageWrapper messageWrapper = new GetBlockHeadersMessageWrapper(headersRequest);
        headerRequest = messageWrapper;

        sendNextHeaderRequest();

        return messageWrapper.getFutureHeaders();
    }

    @Override
    public synchronized ListenableFuture<List<BlockHeader>> sendGetBlockHeaders(byte[] blockHash, int maxBlocksAsk, int skip, boolean reverse) {
        return sendGetBlockHeaders(blockHash, maxBlocksAsk, skip, reverse, false);
    }

    protected synchronized void sendGetNewBlockHeaders(byte[] blockHash, int maxBlocksAsk, int skip, boolean reverse) {
        sendGetBlockHeaders(blockHash, maxBlocksAsk, skip, reverse, true);
    }

    protected synchronized ListenableFuture<List<BlockHeader>> sendGetBlockHeaders(byte[] blockHash, int maxBlocksAsk, int skip, boolean reverse, boolean newHashes) {

        if (peerState != IDLE) return null;

        if(logger.isTraceEnabled()) logger.trace(
                "Peer {}: queue GetBlockHeaders, blockHash [{}], maxBlocksAsk [{}], skip[{}], reverse [{}]",
                channel.getPeerIdShort(),
                "0x" + toHexString(blockHash).substring(0, 8),
                maxBlocksAsk, skip, reverse
        );

        if (headerRequest != null) {
            throw new RuntimeException("The peer is waiting for headers response: " + this);
        }

        GetBlockHeadersMessage headersRequest = new GetBlockHeadersMessage(0, blockHash, maxBlocksAsk, skip, reverse);
        GetBlockHeadersMessageWrapper messageWrapper = new GetBlockHeadersMessageWrapper(headersRequest, newHashes);
        headerRequest = messageWrapper;

        sendNextHeaderRequest();
        lastReqSentTime = System.currentTimeMillis();

        return messageWrapper.getFutureHeaders();
    }

    @Override
    public synchronized ListenableFuture<List<Block>> sendGetBlockBodies(List<BlockHeaderWrapper> headers) {
        if (peerState != IDLE) return null;

        peerState = BLOCK_RETRIEVING;
        sentHeaders.clear();
        sentHeaders.addAll(headers);

        if(logger.isTraceEnabled()) logger.trace(
                "Peer {}: send GetBlockBodies, hashes.count [{}]",
                channel.getPeerIdShort(),
                sentHeaders.size()
        );

        List<byte[]> hashes = new ArrayList<>(headers.size());
        for (BlockHeaderWrapper header : headers) {
            hashes.add(header.getHash());
        }

        GetBlockBodiesMessage msg = new GetBlockBodiesMessage(hashes);

        sendMessage(msg);
        lastReqSentTime = System.currentTimeMillis();

        futureBlocks = SettableFuture.create();
        return futureBlocks;
    }

    @Override
    public synchronized void sendNewBlock(Block block) {
        BigInteger totalRP = block.getCumulativeRewardPoint();
        NewBlockMessage msg = new NewBlockMessage(block, totalRP);
        //ctx.writeAndFlush(msg);
        sendMessage(msg, true);
    }

    /*************************
     *  Message Processing   *
     *************************/

    protected synchronized void processStatus(StatusMessage msg, ChannelHandlerContext ctx) throws InterruptedException {

        try {

            if (!Arrays.equals(msg.getGenesisHash(), config.getGenesis().getHash())) {
                if (!peerDiscoveryMode) {
                    loggerNet.debug("Removing EthHandler for {} due to protocol incompatibility", ctx.channel().remoteAddress());
                }
                ethState = EthState.STATUS_FAILED;
                disconnect(ReasonCode.INCOMPATIBLE_PROTOCOL);
                ctx.pipeline().remove(this); // Peer is not compatible for the 'eth' sub-protocol
                return;
            }

            if (msg.getNetworkId() != config.networkId()) {
                ethState = EthState.STATUS_FAILED;
                disconnect(ReasonCode.NULL_IDENTITY);
                return;
            }

            // basic checks passed, update statistics
            channel.getNodeStatistics().ethHandshake(msg);
            ethereumListener.onEthStatusUpdated(channel, msg);

            if (peerDiscoveryMode) {
                loggerNet.trace("Peer discovery mode: STATUS received, disconnecting...");
                disconnect(ReasonCode.REQUESTED);
                ctx.close().sync();
                ctx.disconnect().sync();
                return;
            }

            coinbase = msg.getCoinbase();

            // update bestKnownBlock info
            sendGetBlockHeaders(msg.getBestHash(), 1, 0, false);

        } catch (NoSuchElementException e) {
            loggerNet.debug("EthHandler already removed");
        }
    }

    protected synchronized void processNewBlockHashes(NewBlockHashesMessage msg) {

        if(logger.isTraceEnabled()) logger.trace(
                "Peer {}: processing NewBlockHashes, size [{}]",
                channel.getPeerIdShort(),
                msg.getBlockIdentifiers().size()
        );

        List<BlockIdentifier> identifiers = msg.getBlockIdentifiers();

        if (identifiers.isEmpty()) return;

        updateBestBlock(identifiers);

        // queueing new blocks doesn't make sense
        // while Long sync is in progress
        if (!syncDone) return;

        if (peerState != HEADER_RETRIEVING) {
            long firstBlockAsk = Long.MAX_VALUE;
            long lastBlockAsk = 0;
            byte[] firstBlockHash = null;
            for (BlockIdentifier identifier : identifiers) {
                long blockNumber = identifier.getNumber();
                if (blockNumber < firstBlockAsk) {
                    firstBlockAsk = blockNumber;
                    firstBlockHash = identifier.getHash();
                }
                if (blockNumber > lastBlockAsk)  {
                    lastBlockAsk = blockNumber;
                }
            }
            long maxBlocksAsk = lastBlockAsk - firstBlockAsk + 1;
            if (firstBlockHash != null && maxBlocksAsk > 0 && maxBlocksAsk < MAX_HASHES_TO_SEND) {
                sendGetNewBlockHeaders(firstBlockHash, (int) maxBlocksAsk, 0, false);
            }
        }
    }

    protected synchronized void processTransactions(TransactionsMessage msg) {
        if(!processTransactions) {
            return;
        }

        List<Transaction> txSet = msg.getTransactions();
        List<Transaction> newPending = pendingState.addPendingTransactions(txSet);
        if (!newPending.isEmpty()) {
            TransactionTask transactionTask = new TransactionTask(newPending, channel.getChannelManager(), channel);
            TransactionExecutor.instance.submitTransaction(transactionTask);
        }
    }


    private synchronized void processMinedBlocks(MinedBlockMessage msg) {
        List<Block> blocks = msg.getBlocks();

        // 전달받은 블록에 추가한다.
        if(blocks.isEmpty()) {
            return;
        }
        receivedBlocks.values().removeIf(number -> number < blocks.get(0).getNumber() - 10);
        for(Block block : blocks) {
            receivedBlocks.put(new ByteArrayWrapper(block.getHash()), block.getNumber());
        }

        if(!processTransactions) {
            // 싱크가 끝나면 processTransactions 값이 true로 변경된다.
            ConsoleUtil.printlnYellow("Sync yet");
            return;
        }


        // 0번 블록은 내 blockchain 내에 존재해야 한다.
        if(!blocks.isEmpty() && !blockstore.isBlockExist(blocks.get(0).getParentHash())) {
            Block oldBlockFirst = blockstore.getChainBlockByNumber(blocks.get(0).getNumber());
            BigInteger oldRP = BigInteger.ZERO;
            if(oldBlockFirst != null) {
                // 싱크가 완료되지 않은 상태이다.
                oldRP = oldBlockFirst.getCumulativeRewardPoint();
            }

            BigInteger newRP = blocks.get(0).getCumulativeRewardPoint();

            if(newRP.compareTo(oldRP) > 0) {
                /* TODO
                 * 256개 조상들의 블럭해더 리스트를 요청하도록 한다.
                 * 만약에 256번 전 조상의 블럭이 존재하지 않는다면 연결을 끊어버리도록 한다.
                 * 존재한다면, 갈라지는 블럭부터 BlockBody를 요청한다.
                 * 해당 블럭들을 DB에 저장한다.
                 */
                sendGetBlockHeaders(blocks.get(0).getNumber(), 100, true);
            } else {
                ConsoleUtil.printlnYellow("mined_2");
                return;
            }

            ConsoleUtil.printlnYellow("mined_3");
            return;
        }

        // 전달받은 블럭들을 검증한다.
        BlockHeaderValidator validator = new CommonConfig().headerValidator();
        for(Block block : blocks) {
            if(!validator.validateAndLog(block.getHeader(), logger)) {
                logger.warn("Received minedBlocks is not valid");
                ConsoleUtil.printlnYellow("mined_4");
                return;
            }
        }

        // 마지막 블럭에 대해서 nonce 값들을 확인힌다.
        if(blocks.size() > 0) {
            Block lastBlock = blocks.get(blocks.size() - 1);
            Block lastParentBlock = blockstore.getBlockByHash(lastBlock.getParentHash());
            HashMap<BigInteger, byte[]> checkedSender = new HashMap<>();
            if(lastParentBlock != null) {
                Repository repo = pendingState.getRepository().getSnapshotTo(lastParentBlock.getStateRoot());
                if(repo != null) {
                    for (Transaction tx : lastBlock.getTransactionsList()) {
                        byte[] sender = tx.getSender();
                        BigInteger senderBi = ByteUtil.bytesToBigInteger(sender);
                        if (checkedSender.get(senderBi) == null) {
                            BigInteger expectedNonce = repo.getNonce(sender);
                            if (!expectedNonce.equals(ByteUtil.bytesToBigInteger(tx.getNonce()))) {
                                return;
                            }
                            checkedSender.put(senderBi, sender);
                        }
                    }
                }
            }
        }



        MinedBlockCache minedBlockCache = MinedBlockCache.getInstance();
        boolean changed = minedBlockCache.compareMinedBlocks(blocks);

        // 변경된 리스트를 전파해야한다.
        if(changed) {
            for(Block block : minedBlockCache.getBestMinedBlocks()) {
                ConsoleUtil.printlnCyan(block.getShortDescr());
            }
            ConsoleUtil.printlnCyan(minedBlockCache.getBestMinedBlocks().size() + " __ " + channel.getInetSocketAddress().getHostString());

            MinedBlockTask minedBlockTask = new MinedBlockTask(blocks, channel.getChannelManager(), channel);
            MinedBlockExecutor.instance.submitMinedBlock(minedBlockTask);
        }
        // 최적의 값을 전달하지 않은 상대에게 블럭을 전달한다.
        else {
            byte[] receivedBestHash;;
            if(!blocks.isEmpty()) {
                receivedBestHash = blocks.get(blocks.size() - 1).getHash();
            } else {
                receivedBestHash = HashUtil.EMPTY_TRIE_HASH;
            }

            List<Block> bestCachedBlocks = minedBlockCache.getBestMinedBlocks();
            if(!bestCachedBlocks.isEmpty()) {
                byte[] cachedBestHash = bestCachedBlocks.get(bestCachedBlocks.size() - 1).getHash();
                if (!FastByteComparisons.equal(receivedBestHash, cachedBestHash)) {
                    ConsoleUtil.printlnRed("Send MinedBLockList " + channel.getInetSocketAddress());
                    sendMessage(new MinedBlockMessage(bestCachedBlocks), true);
                }
            }
        }
    }



    protected synchronized void processGetBlockHeaders(GetBlockHeadersMessage msg) {
        Iterator<BlockHeader> headersIterator = blockchain.getIteratorOfHeadersStartFrom(
                msg.getBlockIdentifier(),
                msg.getSkipBlocks(),
                min(msg.getMaxHeaders(), MAX_HASHES_TO_SEND),
                msg.isReverse()
        );
        List<BlockHeader> blockHeaders = new ArrayList<>();
        while (headersIterator.hasNext()) {
            blockHeaders.add(headersIterator.next());
        }
        BlockHeadersMessage response = new BlockHeadersMessage(blockHeaders);
        sendMessage(response);
    }

    protected synchronized void processBlockHeaders(BlockHeadersMessage msg) {

        if(logger.isTraceEnabled()) logger.trace(
                "Peer {}: processing BlockHeaders, size [{}]",
                channel.getPeerIdShort(),
                msg.getBlockHeaders().size()
        );

        GetBlockHeadersMessageWrapper request = headerRequest;
        headerRequest = null;

        if (!isValid(msg, request)) {
            //if(request.getMessage().getBlockHash() != null) // 이 경우에 연결을 끊으면, 최종적으로 연결이 존재하지 않는다- TODO NULL 이 발생하는 조건을 확인해야함
                dropConnection();

            return;
        }

        List<BlockHeader> received = msg.getBlockHeaders();

        if (ethState == EthState.STATUS_SENT || ethState == EthState.HASH_CONSTRAINTS_CHECK)
            processInitHeaders(received);
        else {
            syncStats.addHeaders(received.size());
            request.getFutureHeaders().set(received);
        }


        // 헤더들을 불러왔으면 body들도 불러오게 한다.
        /*List<BlockHeaderWrapper> headers = new ArrayList<>();

        for(BlockHeader header : received) {
            headers.add(new BlockHeaderWrapper(header, channel.getNodeId()));
        }

        sendGetBlockBodies(headers);*/
        // 여기까지


        processingTime += lastReqSentTime > 0 ? (System.currentTimeMillis() - lastReqSentTime) : 0;
        lastReqSentTime = 0;
        peerState = IDLE;
    }

    protected synchronized void processGetBlockBodies(GetBlockBodiesMessage msg) {
        Iterator<byte[]> bodiesIterator = blockchain.getIteratorOfBodiesByHashes(msg.getBlockHashes());
        List<byte[]> bodies = new ArrayList<>();
        int sizeSum = 0;
        while (bodiesIterator.hasNext()) {
            byte[] body = bodiesIterator.next();
            sizeSum += ByteArrayEstimator.estimateSize(body);
            bodies.add(body);
            if (sizeSum >= MAX_MESSAGE_SIZE) break;
        }
        BlockBodiesMessage response = new BlockBodiesMessage(bodies);
        sendMessage(response);
    }

    private synchronized void processBlockBodies(BlockBodiesMessage msg) {

        if (logger.isTraceEnabled()) logger.trace(
                "Peer {}: process BlockBodies, size [{}]",
                channel.getPeerIdShort(),
                msg.getBlockBodies().size()
        );

        if (!isValid(msg)) {

            dropConnection();
            return;
        }

        syncStats.addBlocks(msg.getBlockBodies().size());

        List<Block> blocks = null;
        try {
            blocks = validateAndMerge(msg);
        } catch (Exception e) {
            logger.info("Fatal validation error while processing block bodies from peer {}", channel.getPeerIdShort());
        }

        if (blocks == null) {
            // headers will be returned by #onShutdown()
            dropConnection();
            return;
        }

        futureBlocks.set(blocks);
        futureBlocks = null;

        processingTime += (System.currentTimeMillis() - lastReqSentTime);
        lastReqSentTime = 0;
        peerState = IDLE;
    }


    protected synchronized void processNewBlock(NewBlockMessage newBlockMessage) {

        Block newBlock = newBlockMessage.getBlock();

        if(TimeUtils.getRealTimestamp() - newBlock.getTimestamp()*1000 < 10_000L) {
            return;
        }
        if(newBlock.getNumber() < bestBlock.getNumber()) {
            return;
        }

        logger.info("New block received: block.index [{}]", newBlock.getNumber());

        updateTotalRewardPoint(newBlockMessage.getTotalRewardPoint());

        updateBestBlock(newBlock);

        if (!syncManager.validateAndAddNewBlock(newBlock, channel.getNodeId())) {
            dropConnection();
        }
    }

    /*************************
     *    Sync Management    *
     *************************/

    @Override
    public synchronized void onShutdown() {
    }

    @Override
    public synchronized void fetchBodies(List<BlockHeaderWrapper> headers) {
        syncStats.reset();
        sendGetBlockBodies(headers);
    }

    protected synchronized void sendNextHeaderRequest() {

        // do not send header requests if status hasn't been passed yet
        if (ethState == EthState.INIT) return;

        GetBlockHeadersMessageWrapper wrapper = headerRequest;

        if (wrapper == null || wrapper.isSent()) return;

        peerState = HEADER_RETRIEVING;

        wrapper.send();
        sendMessage(wrapper.getMessage());
        lastReqSentTime = System.currentTimeMillis();
    }

    protected synchronized void processInitHeaders(List<BlockHeader> received) {

        final BlockHeader blockHeader = received.get(0);
        final long blockNumber = blockHeader.getNumber();

        if (ethState == EthState.STATUS_SENT) {
            updateBestBlock(blockHeader);

            logger.trace("Peer {}: init request succeeded, best known block {}", channel.getPeerIdShort(), bestKnownBlock);

            // checking if the peer has expected block hashes
            ethState = EthState.HASH_CONSTRAINTS_CHECK;

            validatorMap = Collections.synchronizedMap(new HashMap<>());
            List<Pair<Long, BlockHeaderValidator>> validators = config.getBlockchainConfig().getConfigForBlock(blockNumber).headerValidators();
            for (Pair<Long, BlockHeaderValidator> validator : validators) {
                if (validator.getLeft() <= getBestKnownBlock().getNumber()) {
                    validatorMap.put(validator.getLeft(), validator.getRight());
                }
            }

            logger.trace("Peer " + channel.getPeerIdShort() + ": Requested " + validatorMap.size() +
                    " headers for hash check: " + validatorMap.keySet());
            requestNextHashCheck();

        } else {
            BlockHeaderValidator validator = validatorMap.get(blockNumber);
            if (validator != null) {
                BlockHeaderRule.ValidationResult result = validator.validate(blockHeader);
                if (result.success) {
                    validatorMap.remove(blockNumber);
                    requestNextHashCheck();
                } else {
                    logger.debug("Peer {}: wrong fork ({}). Drop the peer and reduce reputation.", channel.getPeerIdShort(), result.error);
                    channel.getNodeStatistics().wrongFork = true;
                    dropConnection();
                }
            }
        }

        if (validatorMap.isEmpty()) {
            ethState = EthState.STATUS_SUCCEEDED;

            logger.trace("Peer {}: all validations passed", channel.getPeerIdShort());
        }
    }

    private void requestNextHashCheck() {
        if (!validatorMap.isEmpty()) {
            final Long checkHeader = validatorMap.keySet().iterator().next();
            sendGetBlockHeaders(checkHeader, 1, false);
            logger.trace("Peer {}: Requested #{} header for hash check.", channel.getPeerIdShort(), checkHeader);
        }
    }


    private void updateBestBlock(Block block) {
        updateBestBlock(block.getHeader());
    }

    private void updateBestBlock(BlockHeader header) {
        if (bestKnownBlock == null || header.getNumber() > bestKnownBlock.getNumber()) {
            bestKnownBlock = new BlockIdentifier(header.getHash(), header.getNumber());
        }
    }

    private void updateBestBlock(List<BlockIdentifier> identifiers) {

        for (BlockIdentifier id : identifiers)
            if (bestKnownBlock == null || id.getNumber() > bestKnownBlock.getNumber()) {
                bestKnownBlock = id;
            }
    }

    @Override
    public BlockIdentifier getBestKnownBlock() {
        return bestKnownBlock;
    }

    private void updateTotalRewardPoint(BigInteger totalRP) {
        channel.getNodeStatistics().setTotalRewardPoint(totalRP);
        this.totalRewardPoint = totalRP;
    }

    @Override
    public BigInteger getTotalRewardPoint() {
        return totalRewardPoint != null ? totalRewardPoint : channel.getNodeStatistics().getEthTotalRewardPoint();
    }

    @Override
    public RewardPoint getCurrentRewardPoint() {
        //return mRewardPoint != null ? mRewardPoint :
        return mRewardPoint;
    }

    /*************************
     *   Getters, setters    *
     *************************/

    @Override
    public boolean isHashRetrievingDone() {
        return peerState == DONE_HASH_RETRIEVING;
    }

    @Override
    public boolean isHashRetrieving() {
        return peerState == HEADER_RETRIEVING;
    }

    @Override
    public boolean hasStatusPassed() {
        return ethState.ordinal() > EthState.HASH_CONSTRAINTS_CHECK.ordinal();
    }

    @Override
    public boolean hasStatusSucceeded() {
        return ethState == EthState.STATUS_SUCCEEDED;
    }

    @Override
    public boolean isIdle() {
        return peerState == IDLE;
    }

    @Override
    public void enableTransactions() {
        processTransactions = true;
    }

    @Override
    public void disableTransactions() {
        processTransactions = false;
    }

    @Override
    public SyncStatistics getStats() {
        return syncStats;
    }

    @Override
    public void onSyncDone(boolean done) {
        syncDone = done;
    }

    /*************************
     *       Validation      *
     *************************/

    @Nullable
    private List<Block> validateAndMerge(BlockBodiesMessage response) {
        // merging received block bodies with requested headers
        // the assumption is the following:
        // - response may miss any bodies present in the request
        // - response may not contain non-requested bodies
        // - order of response bodies should be preserved
        // Otherwise the response is assumed invalid and all bodies are dropped

        List<byte[]> bodyList = response.getBlockBodies();

        Iterator<byte[]> bodies = bodyList.iterator();
        Iterator<BlockHeaderWrapper> wrappers = sentHeaders.iterator();

        List<Block> blocks = new ArrayList<>(bodyList.size());
        List<BlockHeaderWrapper> coveredHeaders = new ArrayList<>(sentHeaders.size());

        boolean blockMerged = true;
        byte[] body = null;
        while (bodies.hasNext() && wrappers.hasNext()) {

            BlockHeaderWrapper wrapper = wrappers.next();
            if (blockMerged) {
                body = bodies.next();
            }

            Block b = new Block.Builder()
                    .withHeader(wrapper.getHeader())
                    .withBody(body)
                    .create();

            if (b == null) {
                blockMerged = false;
            } else {
                blockMerged = true;

                coveredHeaders.add(wrapper);
                blocks.add(b);
            }
        }

        if (bodies.hasNext()) {
            logger.info("Peer {}: invalid BLOCK_BODIES response: at least one block body doesn't correspond to any of requested headers: ",
                    channel.getPeerIdShort(), Hex.toHexString(bodies.next()));
            return null;
        }

        // remove headers covered by response
        sentHeaders.removeAll(coveredHeaders);

        return blocks;
    }

    private boolean isValid(BlockBodiesMessage response) {
        return response.getBlockBodies().size() <= sentHeaders.size();
    }

    protected boolean isValid(BlockHeadersMessage response, GetBlockHeadersMessageWrapper requestWrapper) {

        GetBlockHeadersMessage request = requestWrapper.getMessage();
        List<BlockHeader> headers = response.getBlockHeaders();

        // max headers
        if (headers.size() > request.getMaxHeaders()) {

            if (logger.isInfoEnabled()) logger.info(
                    "Peer {}: invalid response to {}, exceeds maxHeaders limit, headers count={}",
                    channel.getPeerIdShort(), request, headers.size()
            );
             return false;
        }

        // emptiness against best known block
        if (headers.isEmpty()) {

            // initial call after handshake
            if (ethState == EthState.STATUS_SENT || ethState == EthState.HASH_CONSTRAINTS_CHECK) {
                if (logger.isInfoEnabled()) logger.info(
                        "Peer {}: invalid response to initial {}, empty",
                        channel.getPeerIdShort(), request
                );
                return false;
            }

            if (request.getBlockHash() == null &&
                    request.getBlockNumber() <= bestKnownBlock.getNumber()) {

                if (logger.isInfoEnabled()) logger.info(
                        "Peer {}: invalid response to {}, it's empty while bestKnownBlock is {}",
                        channel.getPeerIdShort(), request, bestKnownBlock
                );
                return false;
            }

            return true;
        }

        // first header
        BlockHeader first = headers.get(0);

        if (request.getBlockHash() != null) {
            if (!Arrays.equals(request.getBlockHash(), first.getHash())) {

                if (logger.isInfoEnabled()) logger.info(
                        "Peer {}: invalid response to {}, first header is invalid {}",
                        channel.getPeerIdShort(), request, first
                );
                return false;
            }
        } else {
            if (request.getBlockNumber() != first.getNumber()) {

                if (logger.isInfoEnabled()) logger.info(
                        "Peer {}: invalid response to {}, first header is invalid {}",
                        channel.getPeerIdShort(), request, first
                );
                return false;
            }
        }

        // skip following checks in case of NEW_BLOCK_HASHES handling
        if (requestWrapper.isNewHashesHandling()) return true;

        // numbers and ancestors
        int offset = 1 + request.getSkipBlocks();
        if (request.isReverse()) offset = -offset;

        for (int i = 1; i < headers.size(); i++) {

            BlockHeader cur = headers.get(i);
            BlockHeader prev = headers.get(i - 1);

            long num = cur.getNumber();
            long expectedNum = prev.getNumber() + offset;

            if (num != expectedNum) {
                if (logger.isInfoEnabled()) logger.info(
                        "Peer {}: invalid response to {}, got #{}, expected #{}",
                        channel.getPeerIdShort(), request, num, expectedNum
                );
                return false;
            }

            if (request.getSkipBlocks() == 0) {
                BlockHeader parent;
                BlockHeader child;
                if (request.isReverse()) {
                    parent = cur;
                    child = prev;
                } else {
                    parent = prev;
                    child = cur;
                }
                if (!Arrays.equals(child.getParentHash(), parent.getHash())) {
                    if (logger.isInfoEnabled()) logger.info(
                            "Peer {}: invalid response to {}, got parent hash {} for #{}, expected {}",
                            channel.getPeerIdShort(), request, toHexString(child.getParentHash()),
                            prev.getNumber(), toHexString(parent.getHash())
                    );
                    return false;
                }
            }
        }

        return true;
    }

    @Override
    public synchronized void dropConnection() {
        logger.info("Peer {}: is a bad one, drop", channel.getPeerIdShort());
        disconnect(USELESS_PEER);
    }

    /*************************
     *       Logging         *
     *************************/

    @Override
    public String getSyncStats() {
        int waitResp = lastReqSentTime > 0 ? (int) (System.currentTimeMillis() - lastReqSentTime) / 1000 : 0;
        long lifeTime = System.currentTimeMillis() - connectedTime;
        return String.format(
                "Peer %s: [ %s, %s, %18s, ping %6s ms, rewardPoint %s, best block %s%s]: (idle %s of %s) %s",
                getVersion(),
                channel.getPeerIdShort(),
                AddressUtil.getShortAddress(coinbase),
                peerState,
                (int)channel.getPeerStats().getAvgLatency(),
                getTotalRewardPoint(),
                getBestKnownBlock().getNumber(),
                waitResp > 5 ? ", wait " + waitResp + "s" : " ",
                longToTimePeriod(lifeTime - processingTime),
                longToTimePeriod(lifeTime),
                channel.getNodeStatistics().getClientId());
    }

    protected enum EthState {
        INIT,
        STATUS_SENT,
        HASH_CONSTRAINTS_CHECK,
        STATUS_SUCCEEDED,
        STATUS_FAILED
    }
}
