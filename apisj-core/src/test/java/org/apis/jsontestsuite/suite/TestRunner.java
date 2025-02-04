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
package org.apis.jsontestsuite.suite;

import org.apis.config.CommonConfig;
import org.apis.config.SystemProperties;
import org.apis.core.Block;
import org.apis.core.BlockchainImpl;
import org.apis.core.ImportResult;
import org.apis.core.PendingStateImpl;
import org.apis.core.Repository;
import org.apis.datasource.inmem.HashMapDB;
import org.apis.db.*;
import org.apis.jsontestsuite.suite.builder.BlockBuilder;
import org.apis.jsontestsuite.suite.builder.RepositoryBuilder;
import org.apis.jsontestsuite.suite.model.BlockTck;
import org.apis.jsontestsuite.suite.validators.BlockHeaderValidator;
import org.apis.jsontestsuite.suite.validators.RepositoryValidator;
import org.apis.listener.EthereumListenerAdapter;
import org.apis.util.ByteUtil;
import org.apis.vm.DataWord;
import org.apis.vm.LogInfo;
import org.apis.vm.VM;
import org.apis.vm.program.Program;
import org.apis.vm.program.invoke.ProgramInvoke;
import org.apis.vm.program.invoke.ProgramInvokeFactoryImpl;
import org.apis.vm.program.invoke.ProgramInvokeImpl;
import org.apis.vm.trace.ProgramTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.apis.crypto.HashUtil.shortHash;
import static org.apis.util.ByteUtil.EMPTY_BYTE_ARRAY;
import static org.apis.vm.VMUtils.saveProgramTraceFile;

/**
 * @author Roman Mandeleil
 * @since 02.07.2014
 */
public class TestRunner {

    private Logger logger = LoggerFactory.getLogger("TCK-Test");
    private ProgramTrace trace = null;
    private boolean setNewStateRoot;
    private String bestStateRoot;

    public List<String> runTestSuite(TestSuite testSuite) {

        Iterator<TestCase> testIterator = testSuite.iterator();
        List<String> resultCollector = new ArrayList<>();

        while (testIterator.hasNext()) {

            TestCase testCase = testIterator.next();

            TestRunner runner = new TestRunner();
            List<String> result = runner.runTestCase(testCase);
            resultCollector.addAll(result);
        }

        return resultCollector;
    }


    public List<String> runTestCase(BlockTestCase testCase) {


        /* 1 */ // Create genesis + init pre state
        Block genesis = BlockBuilder.build(testCase.getGenesisBlockHeader(), null, null);
        Repository repository = RepositoryBuilder.build(testCase.getPre());

        IndexedBlockStore blockStore = new IndexedBlockStore();
        blockStore.init(new HashMapDB<byte[]>(), new HashMapDB<byte[]>());
        blockStore.saveBlock(genesis, genesis.getCumulativeDifficulty(), true);

        ProgramInvokeFactoryImpl programInvokeFactory = new ProgramInvokeFactoryImpl();

        BlockchainImpl blockchain = new BlockchainImpl(blockStore, repository)
                .withParentBlockHeaderValidator(CommonConfig.getDefault().parentHeaderValidator());
        blockchain.byTest = true;

        PendingStateImpl pendingState = new PendingStateImpl(new EthereumListenerAdapter(), blockchain);

        blockchain.setBestBlock(genesis);
        blockchain.setTotalDifficulty(genesis.getCumulativeDifficulty());
        blockchain.setParentHeaderValidator(new CommonConfig().parentHeaderValidator());
        blockchain.setProgramInvokeFactory(programInvokeFactory);

        blockchain.setPendingState(pendingState);
        pendingState.setBlockchain(blockchain);

        /* 2 */ // Create block traffic list
        List<Block> blockTraffic = new ArrayList<>();
        for (BlockTck blockTck : testCase.getBlocks()) {
            Block block = BlockBuilder.build(blockTck.getBlockHeader(),
                    blockTck.getTransactions(),
                    blockTck.getUncleHeaders());

            setNewStateRoot = !((blockTck.getTransactions() == null)
                && (blockTck.getUncleHeaders() == null)
                && (blockTck.getBlockHeader() == null));

            Block tBlock = null;
            try {
                byte[] rlp = Utils.parseData(blockTck.getRlp());
                tBlock = new Block(rlp);

                ArrayList<String> outputSummary =
                        BlockHeaderValidator.valid(tBlock.getHeader(), block.getHeader());

                if (!outputSummary.isEmpty()){
                    for (String output : outputSummary)
                        logger.error("{}", output);
                }

                blockTraffic.add(tBlock);
            } catch (Exception e) {
                System.out.println("*** Exception");
            }
        }

        /* 3 */ // Inject blocks to the blockchain execution
        for (Block block : blockTraffic) {

            ImportResult importResult = blockchain.tryToConnect(block);
            logger.debug("{} ~ {} difficulty: {} ::: {}", block.getShortHash(), shortHash(block.getParentHash()),
                    block.getCumulativeDifficulty(), importResult.toString());
        }

        repository = blockchain.getRepository();

        //Check state root matches last valid block
        List<String> results = new ArrayList<>();
        String currRoot = Hex.toHexString(repository.getRoot());

        byte[] bestHash = Hex.decode(testCase.getLastblockhash().startsWith("0x") ?
                testCase.getLastblockhash().substring(2) : testCase.getLastblockhash());
        String finalRoot = Hex.toHexString(blockStore.getBlockByHash(bestHash).getStateRoot());

        if (!finalRoot.equals(currRoot)){
            String formattedString = String.format("Root hash doesn't match best: expected: %s current: %s",
                    finalRoot, currRoot);
            results.add(formattedString);
        }

        Repository postRepository = RepositoryBuilder.build(testCase.getPostState());
        List<String> repoResults = RepositoryValidator.valid(repository, postRepository);
        results.addAll(repoResults);

        return results;
    }


    public List<String> runTestCase(TestCase testCase) {

        logger.info("\n***");
        logger.info(" Running test case: [" + testCase.getName() + "]");
        logger.info("***\n");
        List<String> results = new ArrayList<>();


        logger.info("--------- PRE ---------");
        IterableTestRepository testRepository = new IterableTestRepository(new RepositoryRoot(new HashMapDB<byte[]>()));
        testRepository.environmental = true;
        Repository repository = loadRepository(testRepository, testCase.getPre());

        try {


            /* 2. Create ProgramInvoke - Env/Exec */
            Env env = testCase.getEnv();
            Exec exec = testCase.getExec();
            Logs logs = testCase.getLogs();

            byte[] address = exec.getAddress();
            byte[] origin = exec.getOrigin();
            byte[] caller = exec.getCaller();
            byte[] balance = ByteUtil.bigIntegerToBytes(repository.getBalance(exec.getAddress()));
            byte[] gasPrice = exec.getGasPrice();
            byte[] gas = exec.getGas();
            byte[] callValue = exec.getValue();
            byte[] msgData = exec.getData();
            byte[] lastHash = env.getPreviousHash();
            byte[] coinbase = env.getCurrentCoinbase();
            long timestamp = ByteUtil.byteArrayToLong(env.getCurrentTimestamp());
            long number = ByteUtil.byteArrayToLong(env.getCurrentNumber());
            byte[] difficulty = env.getCurrentDifficulty();
            byte[] gaslimit = env.getCurrentGasLimit();

            // Origin and caller need to exist in order to be able to execute
            if (repository.getAccountState(origin) == null)
                repository.createAccount(origin);
            if (repository.getAccountState(caller) == null)
                repository.createAccount(caller);

            ProgramInvoke programInvoke = new ProgramInvokeImpl(address, origin, caller, balance,
                    gasPrice, gas, callValue, msgData, lastHash, coinbase,
                    timestamp, number, difficulty, gaslimit, repository, new BlockStoreDummy(), true);

            /* 3. Create Program - exec.code */
            /* 4. run VM */
            VM vm = new VM();
            Program program = new Program(exec.getCode(), programInvoke);
            boolean vmDidThrowAnEception = false;
            RuntimeException e = null;
            try {
                while (!program.isStopped())
                    vm.step(program);
            } catch (RuntimeException ex) {
                vmDidThrowAnEception = true;
                e = ex;
            }
            String content = program.getTrace().asJsonString(true);
            saveProgramTraceFile(SystemProperties.getDefault(), testCase.getName(), content);

            if (testCase.getPost() == null) {
                if (!vmDidThrowAnEception) {
                    String output =
                            "VM was expected to throw an exception";
                    logger.info(output);
                    results.add(output);
                } else
                    logger.info("VM did throw an exception: " + e.toString());
            } else {
                if (vmDidThrowAnEception) {
                    String output =
                            "VM threw an unexpected exception: " + e.toString();
                    logger.info(output, e);
                    results.add(output);
                    return results;
                }

                this.trace = program.getTrace();

                logger.info("--------- POST --------");

                /* 5. Assert Post values */
                if (testCase.getPost() != null) {
                    for (ByteArrayWrapper key : testCase.getPost().keySet()) {

                        AccountState accountState = testCase.getPost().get(key);

                        long expectedNonce = accountState.getNonceLong();
                        BigInteger expectedBalance = accountState.getBigIntegerBalance();
                        byte[] expectedCode = accountState.getCode();

                        boolean accountExist = (null != repository.getAccountState(key.getData()));
                        if (!accountExist) {

                            String output =
                                    String.format("The expected account does not exist. key: [ %s ]",
                                            Hex.toHexString(key.getData()));
                            logger.info(output);
                            results.add(output);
                            continue;
                        }

                        long actualNonce = repository.getNonce(key.getData()).longValue();
                        BigInteger actualBalance = repository.getBalance(key.getData());
                        byte[] actualCode = repository.getCode(key.getData());
                        if (actualCode == null) actualCode = "".getBytes();

                        if (expectedNonce != actualNonce) {

                            String output =
                                    String.format("The nonce result is different. key: [ %s ],  expectedNonce: [ %d ] is actualNonce: [ %d ] ",
                                            Hex.toHexString(key.getData()), expectedNonce, actualNonce);
                            logger.info(output);
                            results.add(output);
                        }

                        if (!expectedBalance.equals(actualBalance)) {

                            String output =
                                    String.format("The balance result is different. key: [ %s ],  expectedBalance: [ %s ] is actualBalance: [ %s ] ",
                                            Hex.toHexString(key.getData()), expectedBalance.toString(), actualBalance.toString());
                            logger.info(output);
                            results.add(output);
                        }

                        if (!Arrays.equals(expectedCode, actualCode)) {

                            String output =
                                    String.format("The code result is different. account: [ %s ],  expectedCode: [ %s ] is actualCode: [ %s ] ",
                                            Hex.toHexString(key.getData()),
                                            Hex.toHexString(expectedCode),
                                            Hex.toHexString(actualCode));
                            logger.info(output);
                            results.add(output);
                        }

                        // assert storage
                        Map<DataWord, DataWord> storage = accountState.getStorage();
                        for (DataWord storageKey : storage.keySet()) {

                            byte[] expectedStValue = storage.get(storageKey).getData();

                            ContractDetails contractDetails =
                                    program.getStorage().getContractDetails(accountState.getAddress());

                            if (contractDetails == null) {

                                String output =
                                        String.format("Storage raw doesn't exist: key [ %s ], expectedValue: [ %s ]",
                                                Hex.toHexString(storageKey.getData()),
                                                Hex.toHexString(expectedStValue)
                                        );
                                logger.info(output);
                                results.add(output);
                                continue;
                            }

                            Map<DataWord, DataWord> testStorage = contractDetails.getStorage();
                            DataWord actualValue = testStorage.get(new DataWord(storageKey.getData()));

                            if (actualValue == null ||
                                    !Arrays.equals(expectedStValue, actualValue.getData())) {

                                String output =
                                        String.format("Storage value different: key [ %s ], expectedValue: [ %s ], actualValue: [ %s ]",
                                                Hex.toHexString(storageKey.getData()),
                                                Hex.toHexString(expectedStValue),
                                                actualValue == null ? "" : Hex.toHexString(actualValue.getNoLeadZeroesData()));
                                logger.info(output);
                                results.add(output);
                            }
                        }

                    /* asset logs */
                        List<LogInfo> logResult = program.getResult().getLogInfoList();

                        List<String> logResults = logs.compareToReal(logResult);
                        results.addAll(logResults);
                    }
                }

                // TODO: assert that you have no extra accounts in the repository
                // TODO:  -> basically the deleted by suicide should be deleted
                // TODO:  -> and no unexpected created

                List<org.apis.vm.CallCreate> resultCallCreates =
                        program.getResult().getCallCreateList();

                // assert call creates
                for (int i = 0; i < testCase.getCallCreateList().size(); ++i) {

                    org.apis.vm.CallCreate resultCallCreate = null;
                    if (resultCallCreates != null && resultCallCreates.size() > i) {
                        resultCallCreate = resultCallCreates.get(i);
                    }

                    CallCreate expectedCallCreate = testCase.getCallCreateList().get(i);

                    if (resultCallCreate == null && expectedCallCreate != null) {

                        String output =
                                String.format("Missing call/create invoke: to: [ %s ], data: [ %s ], gas: [ %s ], value: [ %s ]",
                                        Hex.toHexString(expectedCallCreate.getDestination()),
                                        Hex.toHexString(expectedCallCreate.getData()),
                                        Hex.toHexString(expectedCallCreate.getGasLimit()),
                                        Hex.toHexString(expectedCallCreate.getValue()));
                        logger.info(output);
                        results.add(output);

                        continue;
                    }

                    boolean assertDestination = Arrays.equals(
                            expectedCallCreate.getDestination(),
                            resultCallCreate.getDestination());
                    if (!assertDestination) {

                        String output =
                                String.format("Call/Create destination is different. Expected: [ %s ], result: [ %s ]",
                                        Hex.toHexString(expectedCallCreate.getDestination()),
                                        Hex.toHexString(resultCallCreate.getDestination()));
                        logger.info(output);
                        results.add(output);
                    }

                    boolean assertData = Arrays.equals(
                            expectedCallCreate.getData(),
                            resultCallCreate.getData());
                    if (!assertData) {

                        String output =
                                String.format("Call/Create data is different. Expected: [ %s ], result: [ %s ]",
                                        Hex.toHexString(expectedCallCreate.getData()),
                                        Hex.toHexString(resultCallCreate.getData()));
                        logger.info(output);
                        results.add(output);
                    }

                    boolean assertGasLimit = Arrays.equals(
                            expectedCallCreate.getGasLimit(),
                            resultCallCreate.getGasLimit());
                    if (!assertGasLimit) {
                        String output =
                                String.format("Call/Create gasLimit is different. Expected: [ %s ], result: [ %s ]",
                                        Hex.toHexString(expectedCallCreate.getGasLimit()),
                                        Hex.toHexString(resultCallCreate.getGasLimit()));
                        logger.info(output);
                        results.add(output);
                    }

                    boolean assertValue = Arrays.equals(
                            expectedCallCreate.getValue(),
                            resultCallCreate.getValue());
                    if (!assertValue) {
                        String output =
                                String.format("Call/Create value is different. Expected: [ %s ], result: [ %s ]",
                                        Hex.toHexString(expectedCallCreate.getValue()),
                                        Hex.toHexString(resultCallCreate.getValue()));
                        logger.info(output);
                        results.add(output);
                    }
                }

                // assert out
                byte[] expectedHReturn = testCase.getOut();
                byte[] actualHReturn = EMPTY_BYTE_ARRAY;
                if (program.getResult().getHReturn() != null) {
                    actualHReturn = program.getResult().getHReturn();
                }

                if (!Arrays.equals(expectedHReturn, actualHReturn)) {

                    String output =
                            String.format("HReturn is different. Expected hReturn: [ %s ], actual hReturn: [ %s ]",
                                    Hex.toHexString(expectedHReturn),
                                    Hex.toHexString(actualHReturn));
                    logger.info(output);
                    results.add(output);
                }

                // assert gas
                BigInteger expectedGas = new BigInteger(1, testCase.getGas());
                BigInteger actualGas = new BigInteger(1, gas).subtract(BigInteger.valueOf(program.getResult().getGasUsed()));

                if (!expectedGas.equals(actualGas)) {

                    String output =
                            String.format("Gas remaining is different. Expected gas remaining: [ %s ], actual gas remaining: [ %s ]",
                                    expectedGas.toString(),
                                    actualGas.toString());
                    logger.info(output);
                    results.add(output);
                }
                /*
                 * end of if(testCase.getPost().size() == 0)
                 */
            }

            return results;
        } finally {
//          repository.close();
        }
    }

    public org.apis.core.Transaction createTransaction(Transaction tx) {

        byte[] nonceBytes = ByteUtil.longToBytes(tx.nonce);
        byte[] gasPriceBytes = ByteUtil.longToBytes(tx.gasPrice);
        byte[] gasBytes = tx.gasLimit;
        byte[] valueBytes = ByteUtil.longToBytes(tx.value);
        byte[] toAddr = tx.getTo();
        byte[] data = tx.getData();

        org.apis.core.Transaction transaction = new org.apis.core.Transaction(
                nonceBytes, gasPriceBytes, gasBytes,
                toAddr, valueBytes, data);

        return transaction;
    }

    public Repository loadRepository(Repository track, Map<ByteArrayWrapper, AccountState> pre) {


            /* 1. Store pre-exist accounts - Pre */
        for (ByteArrayWrapper key : pre.keySet()) {

            AccountState accountState = pre.get(key);
            byte[] addr = key.getData();

            track.addBalance(addr, new BigInteger(1, accountState.getBalance()));
            track.setNonce(key.getData(), new BigInteger(1, accountState.getNonce()));

            track.saveCode(addr, accountState.getCode());

            for (DataWord storageKey : accountState.getStorage().keySet()) {
                track.addStorageRow(addr, storageKey, accountState.getStorage().get(storageKey));
            }
        }

        return track;
    }


    public ProgramTrace getTrace() {
        return trace;
    }
}
