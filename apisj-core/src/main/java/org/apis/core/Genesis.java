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

import org.apis.db.ByteArrayWrapper;
import org.apis.config.SystemProperties;
import org.apis.util.ByteUtil;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

/**
 * The genesis block is the first block in the chain and has fixed values according to
 * the protocol specification. The genesis block is 13 items, and is specified thus:
 * <p>
 * ( zerohash_256 , SHA3 RLP () , zerohash_160 , stateRoot, 0, 2^22 , 0, 0, 1000000, 0, 0, 0, SHA3 (42) , (), () )
 * <p>
 * - Where zerohash_256 refers to the parent hash, a 256-bit hash which is all zeroes;
 * - zerohash_160 refers to the coinbase address, a 160-bit hash which is all zeroes;
 * - 0 refers to the timestamp (the Unix epoch);
 * - the transaction trie root and extradata are both 0, being equivalent to the empty byte array.
 * - The sequences of both uncles and transactions are empty and represented by ().
 * - SHA3 (42) refers to the SHA3 hash of a byte array of length one whose first and only byte is of value 42.
 * - SHA3 RLP () value refers to the hash of the uncle lists in RLP, both empty lists.
 * <p>
 * See Yellow Paper: http://www.gavwood.com/Paper.pdf (Appendix I. Genesis Block)
 */
public class Genesis extends Block {

    private Map<ByteArrayWrapper, PremineAccount> premine = new HashMap<>();

    public  static byte[] ZERO_HASH_2048 = new byte[256];
    public static long NUMBER = 0;

    private static Block instance;

    public Genesis(byte[] parentHash, byte[] coinbase, byte[] logsBloom,
                   BigInteger rewardPoint, BigInteger cumulativeRewardPoint, long number, long gasLimit,
                   long gasUsed, BigInteger mineralUsed, long timestamp,
                   byte[] extraData, byte[] mixHash, byte[] nonce){
        super(parentHash, coinbase, logsBloom, rewardPoint, cumulativeRewardPoint,
                number, ByteUtil.longToBytesNoLeadZeroes(gasLimit), gasUsed, mineralUsed, timestamp, extraData,
                mixHash, nonce, BigInteger.ZERO, null, null, null, null, null, null, null, null);
    }

    public static Block getInstance() {
        return SystemProperties.getDefault().getGenesis();
    }

    public static Genesis getInstance(SystemProperties config) {
        return config.getGenesis();
    }


    public Map<ByteArrayWrapper, PremineAccount> getPremine() {
        return premine;
    }

    public void setPremine(Map<ByteArrayWrapper, PremineAccount> premine) {
        this.premine = premine;
    }

    public void addPremine(ByteArrayWrapper address, AccountState accountState) {
        premine.put(address, new PremineAccount(accountState));
    }

    public static void populateRepository(Repository repository, Genesis genesis) {
        for (ByteArrayWrapper key : genesis.getPremine().keySet()) {
            final Genesis.PremineAccount premineAccount = genesis.getPremine().get(key);
            final AccountState accountState = premineAccount.accountState;

            repository.createAccount(key.getData());
            repository.setNonce(key.getData(), accountState.getNonce());
            repository.addBalance(key.getData(), accountState.getBalance());
            repository.setAddressMask(key.getData(), accountState.getAddressMask());
            if (premineAccount.code != null) {
                repository.saveCode(key.getData(), premineAccount.code);
            }
        }
    }

    /**
     * Used to keep addition fields.
     */
    public static class PremineAccount {

        public byte[] code;

        public AccountState accountState;

        public byte[] getStateRoot() {
            return accountState.getStateRoot();
        }

        public PremineAccount(AccountState accountState) {
            this.accountState = accountState;
        }

        public PremineAccount() {
        }
    }
}
