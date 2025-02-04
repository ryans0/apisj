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
package org.apis.net;

import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import org.apis.core.Block;
import org.apis.listener.EthereumListener;
import org.apis.mine.MinedBlockCache;
import org.apis.net.eth.message.EthMessage;
import org.apis.net.eth.message.EthMessageCodes;
import org.apis.net.message.Message;
import org.apis.net.message.ReasonCode;
import org.apis.net.p2p.DisconnectMessage;
import org.apis.net.p2p.PingMessage;
import org.apis.net.server.Channel;
import org.apis.util.TimeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.apis.net.message.StaticMessages.DISCONNECT_MESSAGE;

/**
 * This class contains the logic for sending messages in a queue
 *
 * Messages open by send and answered by receive of appropriate message
 *      PING by PONG
 *      GET_PEERS by PEERS
 *      GET_TRANSACTIONS by TRANSACTIONS
 *      GET_BLOCK_HASHES by BLOCK_HASHES
 *      GET_BLOCKS by BLOCKS
 *
 * The following messages will not be answered:
 *      PONG, PEERS, HELLO, STATUS, TRANSACTIONS, BLOCKS
 *
 * @author Roman Mandeleil
 */
@Component
@Scope("prototype")
public class MessageQueue {

    private static final Logger logger = LoggerFactory.getLogger("net");

    private static final ScheduledExecutorService timer = Executors.newScheduledThreadPool(4, new ThreadFactory() {
        private AtomicInteger cnt = new AtomicInteger(0);

        public Thread newThread(Runnable r) {
            return new Thread(r, "MessageQueueTimer-" + cnt.getAndIncrement());
        }
    });

    private final Deque<MessageRoundtrip> requestQueue = new ConcurrentLinkedDeque<>();
    private Deque<MessageRoundtrip> respondQueue = new ConcurrentLinkedDeque<>();
    private ChannelHandlerContext ctx = null;

    @Autowired
    EthereumListener ethereumListener;
    boolean hasPing = false;
    private ScheduledFuture<?> timerTask;
    private Channel channel;

    public MessageQueue() {
    }

    public void activate(ChannelHandlerContext ctx) {
        this.ctx = ctx;
        timerTask = timer.scheduleAtFixedRate(() -> {
            try {
                nudgeQueue();
            } catch (Throwable t) {
                logger.error("Unhandled exception", t);
            }
        }, 10, 10, TimeUnit.MILLISECONDS);
    }

    public void setChannel(Channel channel) {
        this.channel = channel;
    }

    public void sendMessage(Message msg) {
        sendMessage(msg, false);
    }

    public void sendMessage(Message msg, boolean addFirst) {
        if (msg instanceof PingMessage) {
            if (hasPing) return;
            hasPing = true;
        }

        if (msg.getAnswerMessage() != null)
            if(addFirst) {
            requestQueue.addFirst(new MessageRoundtrip(msg));
            } else {
                requestQueue.addLast(new MessageRoundtrip(msg));
            }
        else
            if(addFirst) {
                respondQueue.addFirst(new MessageRoundtrip(msg));
            } else {
                respondQueue.addLast(new MessageRoundtrip(msg));
            }
    }

    public void disconnect() {
        disconnect(DISCONNECT_MESSAGE);
    }

    public void disconnect(ReasonCode reason) {
        disconnect(new DisconnectMessage(reason));
    }

    private void disconnect(DisconnectMessage msg) {
        ctx.writeAndFlush(msg);
        ctx.close();
    }

    public void receivedMessage(Message msg) throws InterruptedException {

        ethereumListener.trace("[Recv: " + msg + "]");

        synchronized (requestQueue) {
            if (requestQueue.peek() != null) {
                MessageRoundtrip messageRoundtrip = requestQueue.peek();
                Message waitingMessage = messageRoundtrip.getMsg();

                if (waitingMessage instanceof PingMessage) hasPing = false;

                if (waitingMessage.getAnswerMessage() != null
                        && msg.getClass() == waitingMessage.getAnswerMessage()) {
                    messageRoundtrip.answer();
                    if (waitingMessage instanceof EthMessage)
                        channel.getPeerStats().pong(messageRoundtrip.lastTimestamp);
                    logger.trace("Message round trip covered: [{}] ",
                            messageRoundtrip.getMsg().getClass());
                }
            }
        }
    }

    private void removeAnsweredMessage(MessageRoundtrip messageRoundtrip) {
        if (messageRoundtrip != null && messageRoundtrip.isAnswered())
            requestQueue.remove();
    }

    private void nudgeQueue() {
        MessageRoundtrip respond = respondQueue.peek();
        MessageRoundtrip request = requestQueue.peek();

        long now = TimeUtils.getRealTimestamp();

        // 전체 시간 중의 50%(0.5sec / 1.0sec) 동안 트랜잭션 전송 못하도록 수정
        if(now % 500L < 250L) {
            if(respond != null && !respond.getMsg().getCommand().equals(EthMessageCodes.TRANSACTIONS)) {
                sendToWire(respondQueue.poll());
            }
            if(request != null && !request.getMsg().getCommand().equals(EthMessageCodes.TRANSACTIONS)) {
                removeAnsweredMessage(requestQueue.peek());
                sendToWire(requestQueue.peek());
            }

        } else {
            // remove last answered message on the queue
            removeAnsweredMessage(requestQueue.peek());
            // Now send the next message
            sendToWire(respondQueue.poll());
            sendToWire(requestQueue.peek());
        }
    }

    private void sendToWire(MessageRoundtrip messageRoundtrip) {
        if (messageRoundtrip != null && messageRoundtrip.getRetryTimes() == 0) {
            // TODO: retry logic || messageRoundtrip.hasToRetry()){

            Message msg = messageRoundtrip.getMsg();

            ethereumListener.onSendMessage(channel, msg);

            ctx.writeAndFlush(msg).addListener(ChannelFutureListener.FIRE_EXCEPTION_ON_FAILURE);

            if (msg.getAnswerMessage() != null) {
                messageRoundtrip.incRetryTimes();
                messageRoundtrip.saveTime();
            }
        }
    }

    public void close() {
        if (timerTask != null) {
            timerTask.cancel(false);
        }
    }
}
