package org.apis;

import org.apis.cli.CLIInterface;
import org.apis.cli.CLIStart;
import org.apis.config.SystemProperties;
import org.apis.core.Block;
import org.apis.core.TransactionReceipt;
import org.apis.db.sql.DBSyncManager;
import org.apis.facade.Apis;
import org.apis.facade.ApisFactory;
import org.apis.listener.EthereumListener;
import org.apis.listener.EthereumListenerAdapter;
import org.apis.rpc.RPCServerManager;
import org.apis.rpc_rest.HttpRpcServer;
import org.apis.util.ConsoleUtil;
import org.apis.util.CurrentStateUtil;
import org.apis.util.TimeUtils;
import org.apis.util.blockchain.ApisUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


public class Start {

    private static Apis mApis;

    private static boolean isRunRpc = false;
    private static boolean synced = false;
    protected static Logger logger = LoggerFactory.getLogger("start");
    /**
     * 마지막으로 블록을 전달 받은 이후, 일정 시간이 지나도 싱크가 안된다면 프로그램을 재시작하고자 한다.
     * 이를 위해 마지막으로 블록을 전달받은 시간을 저장한다.
     */
    private static long timeLastBlockReceived = 0;

    /**
     * 프로그램이 종료된 시간을 기록했다가, 일정 시간이 경과하면 프로그램을 시작시킨다.
     */
    private static long timeLastProgramClosed = 0;

    /**
     * 블록 싱크가 멈춘 후, 프로그램을 종료하기까지 대기하는 시간
     */
    private static final long TIME_CLOSE_WAIT = 3*60*1_000L;

    /**
     * 프로그램 종료 후 다시 시작하기까지 대기하는 시간
     */
    private static final long TIME_RESTART_WAIT = 30*1_000L;

    /**
     * 프로그램이 종료된 상태인지를 나타내는 플래그
     */
    private static boolean isClosed = false;

    static private boolean processRestartThreadCreated = false;

    private static HttpRpcServer httpRpcServer = null;


    public static void main(String args[]) throws IOException {
        CurrentStateUtil.deleteCurrentInfo();

        new CLIStart();
        CLIInterface.call(args);

        // Write the current program's PID to a file
        CurrentStateUtil.storePid();

        final SystemProperties config = SystemProperties.getDefault();
        if(config == null) {
            System.out.println("Failed to load config");
            System.exit(0);
        }


        final boolean actionBlocksLoader = !config.blocksLoader().equals("");

        if (actionBlocksLoader) {
            config.setSyncEnabled(false);
            config.setDiscoveryEnabled(false);
        }

        RPCServerManager rpcServerManager = RPCServerManager.getInstance();

        // Max Peers
        int maxPeers = rpcServerManager.getMaxPeers();
        Map<String, Object> cliOptions = new HashMap<>();
        cliOptions.put("peer.maxActivePeers", String.valueOf(maxPeers));
        SystemProperties.getDefault().overrideParams(cliOptions);

        // Start APIS
        startAPIS();


        if (actionBlocksLoader) {
            mApis.getBlockLoader().loadBlocks();
        }


        // Set Max Peers
        rpcServerManager.setApis(mApis);

        // start server
        if(rpcServerManager.isAvailable()) {
            rpcServerManager.startServer();
            isRunRpc = true;
        }

        httpRpcServer = new HttpRpcServer(mApis);


        // onShutdown, delete the PID file
        // Does not work at abnormal termination
        Runtime.getRuntime().addShutdownHook(new Thread(CurrentStateUtil::deleteCurrentInfo));
    }



    private static void startAPIS() {
        mApis = ApisFactory.createEthereum();
        mApis.addListener(mListener);
        mApis.getBlockMiner().setMinGasPrice(ApisUtil.convert(50, ApisUtil.Unit.nAPIS));
    }

    private static EthereumListener mListener = new EthereumListenerAdapter() {

        @Override
        public void onSyncDone(SyncState state) {
            synced = true;
            logger.debug(ConsoleUtil.colorBRed("\nSYNC DONE =============================================="));


            /*
             *트랜잭션이 없어도 블록을 생성하는 경우(블록타임마다 블록이 생성됨)에만
             * 블록을 받아들이지 못하면 싱크가 되지 않는 것으로 받아들이게 한다.
             */
            if(SystemProperties.getDefault() != null
                    && SystemProperties.getDefault().getBlockchainConfig().getCommonConstants().isBlockGenerateWithoutTx()) {
                /*
                 * 싱크가 완료된 이후, 현재의 싱크 상태를 확인해서 프로그램 재시작 여부를 판단한다.
                 */
                if (!processRestartThreadCreated) {
                    processRestartThreadCreated = true;
                    Executors.newSingleThreadScheduledExecutor().scheduleAtFixedRate(() -> {
                        long now = System.currentTimeMillis();

                        // 싱크가 지연된 경우 프로그램을 종료한다.
                        if (!isClosed && now - timeLastBlockReceived >= TIME_CLOSE_WAIT) {
                            timeLastProgramClosed = now;
                            isClosed = true;
                            mApis.close();

                            // DOCKER 스크립트에서 바로 프로그램을 시작시키도록 되어있다
                            logger.warn("Synchronization has been stopped for a long time. Therefore, restart the program.");
                            System.exit(0);
                        }

                        // 프로그램 종료 후 일정 시간이 경과하면 프로그램을 시작시킨다.
                        if (isClosed && timeLastProgramClosed > 0 && now - timeLastProgramClosed >= TIME_RESTART_WAIT) {
                            startAPIS();
                            timeLastBlockReceived = now;
                            isClosed = false;
                        }

                    }, 60, 1, TimeUnit.SECONDS);
                }
            }
        }

        long blockNumber = 0;

        @Override
        public void onBlock(Block block, List<TransactionReceipt> receipts) {
            blockNumber = block.getNumber();
            logger.debug(ConsoleUtil.colorBBlue("\nOnBlock : %s (%.2f kB)", block.getShortDescr(), block.getEncoded().length/1000f));
            timeLastBlockReceived = TimeUtils.getRealTimestamp();

            // HTTP 서버를 실행시킨다
            boolean isRunHttpRpc = false;
            if(httpRpcServer != null) {
                isRunHttpRpc = httpRpcServer.start();
            }

            // 체인 싱크가 완료되면 SQL 서버 싱크를 시작한다.
            if(synced && (isRunRpc || isRunHttpRpc)) {
                // DB Sync Start
                DBSyncManager.getInstance(mApis).setApis(mApis);
                DBSyncManager.getInstance(mApis).syncThreadStart();
            }

            try {
                if(synced) {
                    CurrentStateUtil.storeLatestBlock(blockNumber);
                }
            } catch (IOException ignored) {}
        }
    };




}
