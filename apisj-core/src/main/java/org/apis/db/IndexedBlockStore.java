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
package org.apis.db;

import org.apis.core.Block;
import org.apis.core.BlockHeader;
import org.apis.datasource.DataSourceArray;
import org.apis.datasource.ObjectDataSource;
import org.apis.datasource.Serializer;
import org.apis.datasource.Source;
import org.apis.util.ByteUtil;
import org.apis.util.FastByteComparisons;
import org.apis.util.RLP;
import org.apis.util.RLPElement;
import org.apis.util.RLPList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.util.encoders.Hex;

import java.io.*;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import static java.math.BigInteger.ZERO;
import static org.apis.crypto.HashUtil.shortHash;
import static org.spongycastle.util.Arrays.areEqual;

public class IndexedBlockStore extends AbstractBlockstore{

    private static final Logger logger = LoggerFactory.getLogger("general");

    Source<byte[], byte[]> indexDS;
    DataSourceArray<List<BlockInfo>> index;
    Source<byte[], byte[]> blocksDS;
    ObjectDataSource<Block> blocks;

    public IndexedBlockStore(){
    }

    public void init(Source<byte[], byte[]> index, Source<byte[], byte[]> blocks) {
        indexDS = index;
        this.index = new DataSourceArray<>(
                new ObjectDataSource<>(index, BLOCK_INFO_SERIALIZER, 512));
        this.blocksDS = blocks;
        this.blocks = new ObjectDataSource<>(blocks, new Serializer<Block, byte[]>() {
            @Override
            public byte[] serialize(Block block) {
                return block.getEncoded();
            }

            @Override
            public Block deserialize(byte[] bytes) {
                return bytes == null ? null : new Block(bytes);
            }
        }, 256);
    }

    public synchronized Block getBestBlock(){

        Long maxLevel = getMaxNumber();
        if (maxLevel < 0) return null;

        Block bestBlock = getChainBlockByNumber(maxLevel);
        if (bestBlock != null) return  bestBlock;

        // That scenario can happen
        // if there is a fork branch that is
        // higher than main branch but has
        // less TD than the main branch TD
        while (bestBlock == null){
            --maxLevel;
            bestBlock = getChainBlockByNumber(maxLevel);
        }

        return bestBlock;
    }

    public synchronized byte[] getBlockHashByNumber(long blockNumber){
        Block chainBlock = getChainBlockByNumber(blockNumber);
        return chainBlock == null ? null : chainBlock.getHash(); // FIXME: can be improved by accessing the hash directly in the index
    }


    @Override
    public synchronized void flush(){
        blocks.flush();
        index.flush();
        blocksDS.flush();
        indexDS.flush();
    }


    @Override
    public synchronized void saveBlock(Block block, BigInteger cummDifficulty, boolean mainChain){
        addInternalBlock(block, cummDifficulty, mainChain);
    }

    /**
     * 저장되는 블록과 같은 높이의 블록들을 확인해서 가장 RP 값이 높은것을 메인체인으로 등록한다.
     */
    private void addInternalBlock(Block block, BigInteger cummRewardPoint, boolean mainChain){

        List<BlockInfo> blockInfos = block.getNumber() >= index.size() ?  null : index.get((int) block.getNumber());
        blockInfos = blockInfos == null ? new ArrayList<>() : blockInfos;

        BlockInfo blockInfo = new BlockInfo();
        blockInfo.setCummRewardPoint(cummRewardPoint);
        blockInfo.setHash(block.getHash());
        blockInfo.setMainChain(mainChain);
        if(mainChain) {
            setParentBest(block.getParentHash());
        }

        // 사전에 등록된 블록이 없으면, 메인 체인으로 등록한다.
        /*if(blockInfos.size() == 0) {
            blockInfo.setMainChain(true);
            setParentBest(blockInfo);
        }*/

        putBlockInfo(blockInfos, blockInfo);

        // 이미 등록된 블록이 1개 이상이라면, MainChain 여부는 RP 값을 비교해서 결정한다.
        /*if(blockInfos.size() > 1) {
            BigInteger maxRP = BigInteger.ZERO;
            for (int i = 0; i < blockInfos.size(); i++) {
                BlockInfo bio = blockInfos.get(i);

                if (bio.getCummRewardPoint().compareTo(maxRP) > 0) {
                    maxRP = bio.getCummRewardPoint();
                }
                bio.setMainChain(false);
                blockInfos.set(i, bio);
            }

            for (int i = 0; i < blockInfos.size(); i++) {
                BlockInfo bio = blockInfos.get(i);
                if (bio.getCummRewardPoint().compareTo(maxRP) == 0) {
                    bio.setMainChain(true);
                    blockInfos.set(i, bio);
                    setParentBest(bio);
                    break;
                }
            }
        }*/
        // MainChain 적용 여기까지.

        index.set((int) block.getNumber(), blockInfos);

        blocks.put(block.getHash(), block);
    }

    private void setParentBest(byte[] parentHash) {
        Block parent = getBlockByHash(parentHash);

        for(int i = 0 ; i < 10; i++) {
            if(parent == null) {
                break;
            }

            List<BlockInfo> blockInfos = parent.getNumber() >= index.size() ? null : index.get((int) parent.getNumber());
            blockInfos = blockInfos == null ? new ArrayList<>() : blockInfos;

            BlockInfo blockInfo = new BlockInfo();
            blockInfo.setCummRewardPoint(parent.getCumulativeRewardPoint());
            blockInfo.setHash(parent.getHash());
            blockInfo.setMainChain(true);

            for(BlockInfo bi : blockInfos) {
                if(FastByteComparisons.equal(bi.getHash(), blockInfo.getHash())) {
                    bi.setMainChain(true);
                } else {
                    bi.setMainChain(false);
                }

                putBlockInfo(blockInfos, bi);
            }

            index.set((int) parent.getNumber(), blockInfos);

            parent = getBlockByHash(parent.getParentHash());
        }
    }

    /**
     * 동일한 블록이 리스트에 존재하면 UPDATE, 없으면 INSERT
     * @param blockInfos List of BlockInfo
     * @param blockInfo BlockInfo added to the list
     */
    private void putBlockInfo(List<BlockInfo> blockInfos, BlockInfo blockInfo) {
        for (int i = 0; i < blockInfos.size(); i++) {
            BlockInfo curBlockInfo = blockInfos.get(i);
            if (FastByteComparisons.equal(curBlockInfo.getHash(), blockInfo.getHash())) {
                blockInfos.set(i, blockInfo);
                return;
            }
        }
        blockInfos.add(blockInfo);
    }


    public synchronized List<Block> getBlocksByNumber(long number){

        List<Block> result = new ArrayList<>();

        if (number >= index.size()) {
            return result;
        }

        List<BlockInfo> blockInfos = index.get((int) number);

        if (blockInfos == null) {
            return result;
        }

        for (BlockInfo blockInfo : blockInfos){

            byte[] hash = blockInfo.getHash();
            Block block = blocks.get(hash);

            result.add(block);
        }
        return result;
    }

    @Override
    public synchronized Block getChainBlockByNumber(long number){
        if (number >= index.size()){
            return null;
        }

        List<BlockInfo> blockInfos = index.get((int) number);

        if (blockInfos == null) {
            return null;
        }

        for (BlockInfo blockInfo : blockInfos){

            if (blockInfo.isMainChain()){

                byte[] hash = blockInfo.getHash();
                return blocks.get(hash);
            }
        }

        return null;
    }

    @Override
    public synchronized Block getBlockByHash(byte[] hash) {
        return blocks.get(hash);
    }

    @Override
    public synchronized boolean isBlockExist(byte[] hash) {
        return blocks.get(hash) != null;
    }


    @Override
    public synchronized BigInteger getTotalRewardPointForHash(byte[] hash){
        Block block = this.getBlockByHash(hash);
        if (block == null) return ZERO;

        Long level  =  block.getNumber();
        List<BlockInfo> blockInfos =  index.get(level.intValue());
        for (BlockInfo blockInfo : blockInfos)
                 if (areEqual(blockInfo.getHash(), hash)) {
                     return blockInfo.cummRewardPoint;
                 }

        return ZERO;
    }


    @Override
    public synchronized BigInteger getTotalRewardPoint(){
        long maxNumber = getMaxNumber();

        List<BlockInfo> blockInfos = index.get((int) maxNumber);
        for (BlockInfo blockInfo : blockInfos){
            if (blockInfo.isMainChain()){
                return blockInfo.getCummRewardPoint();
            }
        }

        while (true){
            --maxNumber;
            List<BlockInfo> infos = getBlockInfoForLevel(maxNumber);

            for (BlockInfo blockInfo : infos) {
                if (blockInfo.isMainChain()) {
                    return blockInfo.getCummRewardPoint();
                }
            }
        }
    }

    public synchronized void updateTotalRewardPoints(long index) {
        List<BlockInfo> level = getBlockInfoForLevel(index);
        for (BlockInfo blockInfo : level) {
            Block block = getBlockByHash(blockInfo.getHash());
            List<BlockInfo> parentInfos = getBlockInfoForLevel(index - 1);
            BlockInfo parentInfo = getBlockInfoForHash(parentInfos, block.getParentHash());
            blockInfo.setCummRewardPoint(parentInfo.getCummRewardPoint().add(block.getRewardPoint()));
        }
        this.index.set((int) index, level);
    }

    @Override
    public synchronized long getMaxNumber(){

        Long bestIndex = 0L;

        if (index.size() > 0){
            bestIndex = (long) index.size();
        }

        return bestIndex - 1L;
    }

    @Override
    public synchronized List<byte[]> getListHashesEndWith(byte[] hash, long number){

        List<Block> blocks = getListBlocksEndWith(hash, number);
        List<byte[]> hashes = new ArrayList<>(blocks.size());

        for (Block b : blocks) {
            hashes.add(b.getHash());
        }

        return hashes;
    }

    @Override
    public synchronized List<BlockHeader> getListHeadersEndWith(byte[] hash, long qty) {

        List<Block> blocks = getListBlocksEndWith(hash, qty);
        List<BlockHeader> headers = new ArrayList<>(blocks.size());

        for (Block b : blocks) {
            headers.add(b.getHeader());
        }

        return headers;
    }

    @Override
    public synchronized List<Block> getListBlocksEndWith(byte[] hash, long qty) {
        return getListBlocksEndWithInner(hash, qty);
    }

    private List<Block> getListBlocksEndWithInner(byte[] hash, long qty) {

        Block block = this.blocks.get(hash);

        if (block == null) return new ArrayList<>();

        List<Block> blocks = new ArrayList<>((int) qty);

        for (int i = 0; i < qty; ++i) {
            blocks.add(block);
            block = this.blocks.get(block.getParentHash());
            if (block == null) break;
        }

        return blocks;
    }

    @Override
    public synchronized void reBranch(Block forkBlock){

        Block bestBlock = getBestBlock();

        long maxLevel = Math.max(bestBlock.getNumber(), forkBlock.getNumber());

        // 1. First ensure that you are one the save level
        long currentLevel = maxLevel;
        Block forkLine = forkBlock;
        if (forkBlock.getNumber() > bestBlock.getNumber()){

            while(currentLevel > bestBlock.getNumber()){
                List<BlockInfo> blocks =  getBlockInfoForLevel(currentLevel);
                BlockInfo blockInfo = getBlockInfoForHash(blocks, forkLine.getHash());
                if (blockInfo != null)  {
                    blockInfo.setMainChain(true);
                    setBlockInfoForLevel(currentLevel, blocks);
                }
                forkLine = getBlockByHash(forkLine.getParentHash());
                --currentLevel;
            }
        }

        Block bestLine = bestBlock;
        if (bestBlock.getNumber() > forkBlock.getNumber()){

            while(currentLevel > forkBlock.getNumber()){

                List<BlockInfo> blocks =  getBlockInfoForLevel(currentLevel);
                BlockInfo blockInfo = getBlockInfoForHash(blocks, bestLine.getHash());
                if (blockInfo != null)  {
                    blockInfo.setMainChain(false);
                    setBlockInfoForLevel(currentLevel, blocks);
                }
                bestLine = getBlockByHash(bestLine.getParentHash());
                --currentLevel;
            }
        }


        // 2. Loop back on each level until common block
        while( !bestLine.isEqual(forkLine) ) {

            List<BlockInfo> levelBlocks = getBlockInfoForLevel(currentLevel);
            BlockInfo bestInfo = getBlockInfoForHash(levelBlocks, bestLine.getHash());
            if (bestInfo != null) {
                bestInfo.setMainChain(false);
                setBlockInfoForLevel(currentLevel, levelBlocks);
            }

            BlockInfo forkInfo = getBlockInfoForHash(levelBlocks, forkLine.getHash());
            if (forkInfo != null) {
                forkInfo.setMainChain(true);
                setBlockInfoForLevel(currentLevel, levelBlocks);
            }


            bestLine = getBlockByHash(bestLine.getParentHash());
            forkLine = getBlockByHash(forkLine.getParentHash());
            --currentLevel;
        }


    }


    public synchronized List<byte[]> getListHashesStartWith(long number, long maxBlocks){

        List<byte[]> result = new ArrayList<>();

        int i;
        for ( i = 0; i < maxBlocks; ++i){
            List<BlockInfo> blockInfos =  index.get((int) number);
            if (blockInfos == null) break;

            for (BlockInfo blockInfo : blockInfos)
               if (blockInfo.isMainChain()){
                   result.add(blockInfo.getHash());
                   break;
               }

            ++number;
        }
        maxBlocks -= i;

        return result;
    }

    public static class BlockInfo implements Serializable {
        byte[] hash;
        BigInteger cummRewardPoint = BigInteger.ZERO;
        boolean mainChain;

        public byte[] getHash() {
            return hash;
        }

        public void setHash(byte[] hash) {
            this.hash = hash;
        }

        BigInteger getCummRewardPoint() {
            return cummRewardPoint;
        }

        void setCummRewardPoint(BigInteger cummRewardPoint) {
            this.cummRewardPoint = cummRewardPoint;
        }

        public boolean isMainChain() {
            return mainChain;
        }

        public void setMainChain(boolean mainChain) {
            this.mainChain = mainChain;
        }
    }


    public static final Serializer<List<BlockInfo>, byte[]> BLOCK_INFO_SERIALIZER = new Serializer<List<BlockInfo>, byte[]>(){

        @Override
        public byte[] serialize(List<BlockInfo> value) {
                List<byte[]> rlpBlockInfoList = new ArrayList<>();
                for (BlockInfo blockInfo : value) {
                    byte[] hash = RLP.encodeElement(blockInfo.getHash());
                    // Encoding works correctly only with positive BigIntegers
                    if (blockInfo.getCummRewardPoint() == null || blockInfo.getCummRewardPoint().compareTo(BigInteger.ZERO) < 0) {
                        throw new RuntimeException("BlockInfo cummRewardPoint should be positive BigInteger");
                    }
                    byte[] cummRP = RLP.encodeBigInteger(blockInfo.getCummRewardPoint());
                    byte[] isMainChain = RLP.encodeInt(blockInfo.isMainChain() ? 1 : 0);
                    rlpBlockInfoList.add(RLP.encodeList(hash, cummRP, isMainChain));
                }
                byte[][] elements = rlpBlockInfoList.toArray(new byte[rlpBlockInfoList.size()][]);

                return RLP.encodeList(elements);
        }

        @Override
        public List<BlockInfo> deserialize(byte[] bytes) {
            if (bytes == null) return null;

            List<BlockInfo> blockInfoList = new ArrayList<>();
            RLPList list = (RLPList) RLP.decode2(bytes).get(0);
            for (RLPElement element : list) {
                RLPList rlpBlock = (RLPList) element;
                BlockInfo blockInfo = new BlockInfo();
                byte[] rlpHash = rlpBlock.get(0).getRLPData();
                blockInfo.setHash(rlpHash == null ? new byte[0] : rlpHash);
                byte[] rlpCummRP = rlpBlock.get(1).getRLPData();
                blockInfo.setCummRewardPoint(rlpCummRP == null ? BigInteger.ZERO : ByteUtil.bytesToBigInteger(rlpCummRP));
                blockInfo.setMainChain(ByteUtil.byteArrayToInt(rlpBlock.get(2).getRLPData()) == 1);
                blockInfoList.add(blockInfo);
            }

            return blockInfoList;
        }
    };


    public synchronized void printChain(){

        Long number = getMaxNumber();

        for (int i = 0; i < number; ++i){
            List<BlockInfo> levelInfos = index.get(i);

            if (levelInfos != null) {
                System.out.print(i);
                for (BlockInfo blockInfo : levelInfos){
                    if (blockInfo.isMainChain())
                        System.out.print(" [" + shortHash(blockInfo.getHash()) + "] ");
                    else
                        System.out.print(" " + shortHash(blockInfo.getHash()) + " ");
                }
                System.out.println();
            }

        }

    }

    private synchronized List<BlockInfo> getBlockInfoForLevel(long level){
        return index.get((int) level);
    }

    private synchronized void setBlockInfoForLevel(long level, List<BlockInfo> infos){
        index.set((int) level, infos);
    }

    private static BlockInfo getBlockInfoForHash(List<BlockInfo> blocks, byte[] hash){

        for (BlockInfo blockInfo : blocks)
            if (areEqual(hash, blockInfo.getHash())) return blockInfo;

        return null;
    }

    @Override
    public synchronized void load() {
    }

    @Override
    public synchronized void close() {
//        logger.info("Closing IndexedBlockStore...");
//        try {
//            indexDS.close();
//        } catch (Exception e) {
//            logger.warn("Problems closing indexDS", e);
//        }
//        try {
//            blocksDS.close();
//        } catch (Exception e) {
//            logger.warn("Problems closing blocksDS", e);
//        }
    }
}
