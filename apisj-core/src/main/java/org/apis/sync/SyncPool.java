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
package org.apis.sync;

import org.apis.core.Blockchain;
import org.apis.net.message.ReasonCode;
import org.apis.net.rlpx.Node;
import org.apis.net.rlpx.discover.NodeHandler;
import org.apis.net.rlpx.discover.NodeManager;
import org.apis.net.server.Channel;
import org.apis.net.server.ChannelManager;
import org.apis.config.SystemProperties;
import org.apis.listener.EthereumListener;
import org.apis.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.Nullable;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import static java.lang.Math.max;
import static java.lang.Math.min;
import static org.apis.util.BIUtil.isIn20PercentRange;
import static org.apis.util.ByteUtil.toHexString;

/**
 * <p>Encapsulates logic which manages peers involved in blockchain sync</p>
 *
 * Holds connections, bans, disconnects and other peers logic<br>
 * The pool is completely threadsafe<br>
 * Implements {@link Iterable} and can be used in "foreach" loop<br>
 * Used by {@link SyncManager}
 *
 * @author Mikhail Kalinin
 * @since 10.08.2015
 */
@Component
public class SyncPool {

    public static final Logger logger = LoggerFactory.getLogger("sync");

    private static final long WORKER_TIMEOUT = 3; // 3 seconds

    private final List<Channel> activePeers = Collections.synchronizedList(new ArrayList<>());

    private BigInteger lowerUsefulRewardPoint = BigInteger.ZERO;

    @Autowired
    private EthereumListener ethereumListener;

    @Autowired
    private NodeManager nodeManager;

    private ChannelManager channelManager;

    private Blockchain blockchain;

    private SystemProperties config;

    private ScheduledExecutorService poolLoopExecutor = Executors.newSingleThreadScheduledExecutor();

    private Predicate<NodeHandler> nodesSelector;
    private ScheduledExecutorService logExecutor = Executors.newSingleThreadScheduledExecutor();

    @Autowired
    public SyncPool(final SystemProperties config) {
        this.config = config;
    }

    public void init(final ChannelManager channelManager, final Blockchain blockchain) {
        if (this.channelManager != null) return; // inited already
        this.channelManager = channelManager;
        this.blockchain = blockchain;
        updateLowerUsefulRewardPoint();

        poolLoopExecutor.scheduleWithFixedDelay(() -> {
            try {
                heartBeat();
                updateLowerUsefulRewardPoint();
                prepareActive();
                fillUp();
                cleanupActive();
            } catch (Throwable t) {
                logger.error("Unhandled exception", t);
            }
        }, WORKER_TIMEOUT, WORKER_TIMEOUT, TimeUnit.SECONDS);
        logExecutor.scheduleWithFixedDelay(() -> {
            try {
                logActivePeers();
                logger.info("\n");
            } catch (Throwable t) {
                t.printStackTrace();
                logger.error("Exception in log worker", t);
            }
        }, 30, 30, TimeUnit.SECONDS);
    }


    public void setNodesSelector(Predicate<NodeHandler> nodesSelector) {
        this.nodesSelector = nodesSelector;
    }

    public void close() {
        try {
            poolLoopExecutor.shutdownNow();
            logExecutor.shutdownNow();
        } catch (Exception e) {
            logger.warn("Problems shutting down executor", e);
        }
    }

    @Nullable
    public synchronized Channel getAnyIdle() {
        ArrayList<Channel> channels = new ArrayList<>(activePeers);
        Collections.shuffle(channels);
        for (Channel peer : channels) {
            if (peer.isIdle())
                return peer;
        }

        return null;
    }

    public synchronized Channel getAnyPeer() {
        ArrayList<Channel> channels = new ArrayList<>(activePeers);
        Collections.shuffle(channels);
        for (Channel peer : channels) {
            if (peer.isIdle() || peer.isHashRetrieving() || peer.isActive())
                return peer;
        }

        return null;
    }

    @Nullable
    public synchronized Channel getBestIdle() {
        for (Channel peer : activePeers) {
            if (peer.isIdle())
                return peer;
        }
        return null;
    }

    @Nullable
    public synchronized Channel getNotLastIdle() {
        ArrayList<Channel> channels = new ArrayList<>(activePeers);
        Collections.shuffle(channels);
        Channel candidate = null;
        for (Channel peer : channels) {
            if (peer.isIdle()) {
                if (candidate == null) {
                    candidate = peer;
                } else {
                    return candidate;
                }
            }
        }

        return null;
    }

    public synchronized List<Channel> getAllIdle() {
        List<Channel> ret = new ArrayList<>();
        for (Channel peer : activePeers) {
            if (peer.isIdle())
                ret.add(peer);
        }
        return ret;
    }

    public synchronized List<Channel> getActivePeers() {
        return new ArrayList<>(activePeers);
    }

    public synchronized int getActivePeersCount() {
        return activePeers.size();
    }

    @Nullable
    public synchronized Channel getByNodeId(byte[] nodeId) {
        return channelManager.getActivePeer(nodeId);
    }

    public synchronized void onDisconnect(Channel peer) {
        if (activePeers.remove(peer)) {
            logger.info("Peer {}: disconnected", peer.getPeerIdShort());
        }
    }

    public synchronized Set<String> nodesInUse() {
        Set<String> ids = new HashSet<>();
        for (Channel peer : channelManager.getActivePeers()) {
            ids.add(peer.getPeerId());
        }
        return ids;
    }

    synchronized void logActivePeers() {
        if (logger.isInfoEnabled()) {
            StringBuilder sb = new StringBuilder("Peer stats:\n");
            sb.append("Active peers\n");
            sb.append("============\n");
            Set<Node> activeSet = new HashSet<>();
            for (Channel peer : new ArrayList<>(activePeers)) {
                sb.append(peer.logSyncStats()).append('\n');
                activeSet.add(peer.getNode());
            }
            sb.append("Other connected peers\n");
            sb.append("============\n");
            for (Channel peer : new ArrayList<>(channelManager.getActivePeers())) {
                if (!activeSet.contains(peer.getNode())) {
                    sb.append(peer.logSyncStats()).append('\n');
                }
            }
            logger.info(sb.toString());
        }
    }

    class NodeSelector implements Predicate<NodeHandler> {
        BigInteger lowerDifficulty;
        Set<String> nodesInUse;

        public NodeSelector(BigInteger lowerDifficulty) {
            this.lowerDifficulty = lowerDifficulty;
        }

        public NodeSelector(BigInteger lowerDifficulty, Set<String> nodesInUse) {
            this.lowerDifficulty = lowerDifficulty;
            this.nodesInUse = nodesInUse;
        }

        @Override
        public boolean test(NodeHandler handler) {
            if (nodesInUse != null && nodesInUse.contains(handler.getNode().getHexId())) {
                return false;
            }

            if (handler.getNodeStatistics().isPredefined()) return true;

            if (nodesSelector != null && !nodesSelector.test(handler)) return false;

            if (lowerDifficulty.compareTo(BigInteger.ZERO) > 0 &&
                    handler.getNodeStatistics().getEthTotalRewardPoint() == null) {
                return false;
            }

            if (handler.getNodeStatistics().getReputation() < 100) return false;

            return handler.getNodeStatistics().getEthTotalRewardPoint().compareTo(lowerDifficulty) >= 0;
        }
    }

    private void fillUp() {
        int lackSize = config.maxActivePeers() - channelManager.getActivePeers().size();
        if(lackSize <= 0) return;

        final Set<String> nodesInUse = nodesInUse();
        nodesInUse.add(Hex.toHexString(config.nodeId()));   // exclude home node

        List<NodeHandler> newNodes;
        newNodes = nodeManager.getNodes(new NodeSelector(lowerUsefulRewardPoint, nodesInUse), lackSize);
        if (lackSize > 0 && newNodes.isEmpty()) {
            newNodes = nodeManager.getNodes(new NodeSelector(BigInteger.ZERO, nodesInUse), lackSize);
        }

        if (logger.isTraceEnabled()) {
            logDiscoveredNodes(newNodes);
        }

        for(NodeHandler n : newNodes) {
            channelManager.connect(n.getNode());
        }
    }

    private synchronized void prepareActive() {
        List<Channel> managerActive = new ArrayList<>(channelManager.getActivePeers());
        if (logger.isTraceEnabled())
            logger.trace("Preparing active peers from {} channelManager peers", managerActive.size());

        // Filtering out with nodeSelector because server-connected nodes were not tested
        NodeSelector nodeSelector = new NodeSelector(BigInteger.ZERO);
        List<Channel> active = new ArrayList<>();
        for (Channel channel : managerActive) {
            if (nodeSelector.test(nodeManager.getNodeHandler(channel.getNode()))) {
                active.add(channel);
            }
        }

        if (logger.isTraceEnabled())
            logger.trace("After filtering out with node selector, {} peers remaining", active.size());
        if (active.isEmpty()) return;

        // filtering by 20% from top difficulty
        active.sort((c1, c2) -> c2.getTotalRewardPoint().compareTo(c1.getTotalRewardPoint()));

        BigInteger highestRewardPoint = active.get(0).getTotalRewardPoint();
        int thresholdIdx = min(config.syncPeerCount(), active.size()) - 1;

        for (int i = thresholdIdx; i >= 0; i--) {
            if (isIn20PercentRange(active.get(i).getTotalRewardPoint(), highestRewardPoint)) {
                thresholdIdx = i;
                break;
            }
        }

        List<Channel> filtered = active.subList(0, thresholdIdx + 1);

        // Dropping other peers to free up slots for active
        // Act more aggressive until sync is done
        int cap = channelManager.getSyncManager().isSyncDone() ?
                // 10 peers are enough for variance in data on short sync
                Math.max(config.maxActivePeers() / 2, config.maxActivePeers() - 10) : config.maxActivePeers() / 6;
        int otherCount = managerActive.size() - filtered.size();
        int killCount = max(0, otherCount - cap);
        if (killCount > 0) {
            AtomicInteger dropped = new AtomicInteger(0);
            for (Channel channel : managerActive) {
                if (!filtered.contains(channel)) {
                    if (channel.isIdle()) {
                        channelManager.disconnect(channel, ReasonCode.TOO_MANY_PEERS);
                        if (dropped.incrementAndGet() >= killCount) break;
                    }
                }
            }
            logger.debug("Dropped {} other peers to free up sync slots", dropped.get());
        }


        for (Channel channel : filtered) {
            if (!activePeers.contains(channel)) {
                ethereumListener.onPeerAddedToSyncPool(channel);
            }
        }
        if (logger.isTraceEnabled())
            logger.trace("{} peers set to be active in SyncPool", filtered.size());

        activePeers.clear();
        activePeers.addAll(filtered);
    }

    private synchronized void cleanupActive() {
        Iterator<Channel> iterator = activePeers.iterator();
        while (iterator.hasNext()) {
            Channel next = iterator.next();
            if (next.isDisconnected()) {
                logger.info("Removing peer " + next + " from active due to disconnect.");
                iterator.remove();
            }
        }
    }


    private void logDiscoveredNodes(List<NodeHandler> nodes) {
        StringBuilder sb = new StringBuilder();
        for(NodeHandler n : nodes) {
            sb.append(Utils.getNodeIdShort(Hex.toHexString(n.getNode().getId())));
            sb.append(", ");
        }
        if(sb.length() > 0) {
            sb.delete(sb.length() - 2, sb.length());
        }
        logger.trace(
                "Node list obtained from discovery: {}",
                nodes.size() > 0 ? sb.toString() : "empty"
        );
    }

    private void updateLowerUsefulRewardPoint () {
        BigInteger rp = blockchain.getTotalRewardPoint();
        if (rp.compareTo(lowerUsefulRewardPoint) > 0) {
            lowerUsefulRewardPoint = rp;
        }
    }

    public ChannelManager getChannelManager() {
        return channelManager;
    }

    private void heartBeat() {
//        for (Channel peer : channelManager.getActivePeers()) {
//            if (!peer.isIdle() && peer.getSyncStats().secondsSinceLastUpdate() > config.peerChannelReadTimeout()) {
//                logger.info("Peer {}: no response after {} seconds", peer.getPeerIdShort(), config.peerChannelReadTimeout());
//                peer.dropConnection();
//            }
//        }
    }
}
