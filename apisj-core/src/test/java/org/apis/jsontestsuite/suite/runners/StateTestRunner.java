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
package org.apis.jsontestsuite.suite.runners;

import org.apis.config.SystemProperties;
import org.apis.config.net.MainNetConfig;
import org.apis.core.*;
import org.apis.db.BlockStoreDummy;
import org.apis.jsontestsuite.suite.Env;
import org.apis.jsontestsuite.suite.StateTestCase;
import org.apis.jsontestsuite.suite.TestProgramInvokeFactory;
import org.apis.jsontestsuite.suite.builder.BlockBuilder;
import org.apis.jsontestsuite.suite.builder.EnvBuilder;
import org.apis.jsontestsuite.suite.builder.RepositoryBuilder;
import org.apis.jsontestsuite.suite.builder.TransactionBuilder;
import org.apis.jsontestsuite.suite.validators.OutputValidator;
import org.apis.jsontestsuite.suite.validators.RepositoryValidator;
import org.apis.vm.LogInfo;
import org.apis.vm.program.ProgramResult;
import org.apis.vm.program.invoke.ProgramInvokeFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;

import java.util.ArrayList;
import java.util.List;

public class StateTestRunner {

    private static Logger logger = LoggerFactory.getLogger("TCK-Test");

    public static List<String> run(StateTestCase stateTestCase2) {
        try {
            SystemProperties.getDefault().setBlockchainConfig(stateTestCase2.getConfig());
            return new StateTestRunner(stateTestCase2).runImpl();
        } finally {
            SystemProperties.getDefault().setBlockchainConfig(MainNetConfig.INSTANCE);
        }
    }

    protected StateTestCase stateTestCase;
    protected Repository repository;
    protected Transaction transaction;
    protected BlockchainImpl blockchain;
    protected Env env;
    protected ProgramInvokeFactory invokeFactory;
    protected Block block;

    public StateTestRunner(StateTestCase stateTestCase) {
        this.stateTestCase = stateTestCase;
    }

    protected ProgramResult executeTransaction(Transaction tx) {
        Repository track = repository.startTracking();

        TransactionExecutor executor =
                new TransactionExecutor(transaction, env.getCurrentCoinbase(), track, new BlockStoreDummy(),
                        invokeFactory, blockchain.getBestBlock());

        try{
            executor.init();
            executor.execute();
            executor.go();
            executor.finalization();
        } catch (StackOverflowError soe){
            logger.error(" !!! StackOverflowError: update your java run command with -Xss2M !!!");
            System.exit(-1);
        }

        track.commit();
        return executor.getResult();
    }

    public List<String> runImpl() {

        logger.info("");
        repository = RepositoryBuilder.build(stateTestCase.getPre());
        logger.info("loaded repository");

        transaction = TransactionBuilder.build(stateTestCase.getTransaction());
        logger.info("transaction: {}", transaction.toString());

        blockchain = new BlockchainImpl();
        blockchain.setRepository(repository);

        env = EnvBuilder.build(stateTestCase.getEnv());
        invokeFactory = new TestProgramInvokeFactory(env);

        block = BlockBuilder.build(env);

        blockchain.setBestBlock(block);
        blockchain.setProgramInvokeFactory(invokeFactory);

        ProgramResult programResult = executeTransaction(transaction);

        repository.commit();

        List<LogInfo> origLogs = programResult.getLogInfoList();
        List<String> logsResult = stateTestCase.getLogs().compareToReal(origLogs);

        List<String> results = new ArrayList<>();

        if (stateTestCase.getPost() != null) {
            Repository postRepository = RepositoryBuilder.build(stateTestCase.getPost());
            List<String> repoResults = RepositoryValidator.valid(repository, postRepository);

            results.addAll(repoResults);
        } else if (stateTestCase.getPostStateRoot() != null) {
            results.addAll(RepositoryValidator.validRoot(
                    Hex.toHexString(repository.getRoot()),
                    stateTestCase.getPostStateRoot()
            ));
        }

        if (stateTestCase.getOut() != null) {
            List<String> outputResults =
                    OutputValidator.valid(Hex.toHexString(programResult.getHReturn()), stateTestCase.getOut());
            results.addAll(outputResults);
        }

        results.addAll(logsResult);

        logger.info("--------- POST Validation---------");
        for (String result : results) {
            logger.error(result);
        }

        logger.info("\n\n");
        return results;
    }
}
