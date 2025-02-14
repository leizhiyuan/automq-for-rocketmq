/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.automq.stream.s3.wal;

import com.automq.stream.s3.DirectByteBufAlloc;
import com.automq.stream.s3.wal.util.WALChannel;
import com.automq.stream.s3.wal.util.WALUtil;
import com.automq.stream.utils.FutureUtil;
import com.automq.stream.utils.ThreadUtils;
import com.automq.stream.utils.Threads;
import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedList;
import java.util.Queue;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.automq.stream.s3.wal.BlockWALService.RECORD_HEADER_MAGIC_CODE;
import static com.automq.stream.s3.wal.BlockWALService.RECORD_HEADER_SIZE;
import static com.automq.stream.s3.wal.BlockWALService.RECORD_HEADER_WITHOUT_CRC_SIZE;
import static com.automq.stream.s3.wal.BlockWALService.WAL_HEADER_TOTAL_CAPACITY;
import static com.automq.stream.s3.wal.WriteAheadLog.AppendResult;
import static com.automq.stream.s3.wal.WriteAheadLog.OverCapacityException;

/**
 * The sliding window contains all records that have not been flushed to the disk yet.
 * All records are written to the disk asynchronously by the AIO thread pool.
 * When the sliding window is full, the current thread will be blocked until the sliding window is expanded.
 * When the asynchronous write is completed, the start offset of the sliding window will be updated.
 */
public class SlidingWindowService {
    private static final Logger LOGGER = LoggerFactory.getLogger(SlidingWindowService.class.getSimpleName());
    private final int ioThreadNums;
    private final long upperLimit;
    private final long scaleUnit;
    private final long blockSoftLimit;
    private final WALChannel walChannel;
    private final WALHeaderFlusher walHeaderFlusher;
    private final WindowCoreData windowCoreData = new WindowCoreData();
    /**
     * The lock of {@link #pendingBlocks}, {@link #writingBlocks}, {@link #currentBlock}.
     */
    private final Lock blockLock = new ReentrantLock();
    /**
     * Blocks that are waiting to be written.
     * All blocks in this queue are ordered by the start offset.
     */
    private final Queue<Block> pendingBlocks = new LinkedList<>();
    /**
     * Blocks that are being written.
     */
    private final TreeSet<Long> writingBlocks = new TreeSet<>();
    private ExecutorService executorService;
    private Semaphore semaphore;
    /**
     * The current block, records are added to this block.
     */
    private Block currentBlock;

    public SlidingWindowService(WALChannel walChannel, int ioThreadNums, long upperLimit, long scaleUnit, long blockSoftLimit, WALHeaderFlusher flusher) {
        this.walChannel = walChannel;
        this.ioThreadNums = ioThreadNums;
        this.upperLimit = upperLimit;
        this.scaleUnit = scaleUnit;
        this.blockSoftLimit = blockSoftLimit;
        this.walHeaderFlusher = flusher;
    }

    public void resetWindowWhenRecoverOver(long startOffset, long nextWriteOffset, long maxLength) {
        windowCoreData.setWindowStartOffset(startOffset);
        windowCoreData.setWindowNextWriteOffset(nextWriteOffset);
        windowCoreData.setWindowMaxLength(maxLength);
    }

    public void resetWindow(long offset) {
        windowCoreData.setWindowStartOffset(offset);
        windowCoreData.setWindowNextWriteOffset(offset);
    }

    public WindowCoreData getWindowCoreData() {
        return windowCoreData;
    }

    public void start() throws IOException {
        this.executorService = Threads.newFixedThreadPool(ioThreadNums,
                ThreadUtils.createThreadFactory("block-wal-io-thread-%d", false), LOGGER);
        this.semaphore = new Semaphore(ioThreadNums);
    }

    public boolean shutdown(long timeout, TimeUnit unit) {
        boolean gracefulShutdown;

        this.executorService.shutdown();
        try {
            gracefulShutdown = this.executorService.awaitTermination(timeout, unit);
        } catch (InterruptedException e) {
            this.executorService.shutdownNow();
            gracefulShutdown = false;
        }

        if (gracefulShutdown) {
            this.getWindowCoreData().setWindowNextWriteOffset(this.getWindowCoreData().getWindowStartOffset());
        }

        return gracefulShutdown;
    }

    /**
     * Try to write a block. If the semaphore is not available, it will return immediately.
     */
    public void tryWriteBlock() {
        if (semaphore.tryAcquire()) {
            Block block = pollBlock();
            if (block != null) {
                executorService.submit(new WriteBlockProcessor(block));
            } else {
                semaphore.release();
            }
        }
    }

    public Lock getBlockLock() {
        return blockLock;
    }

    /**
     * Seal and create a new block. It
     * - puts the previous block to the write queue
     * - creates a new block, sets it as the current block and returns it
     * Note: this method is NOT thread safe, and it should be called with {@link #blockLock} locked.
     */
    public Block sealAndNewBlockLocked(Block previousBlock, long minSize, long trimOffset, long recordSectionCapacity) throws OverCapacityException {
        long startOffset = nextBlockStartOffset(previousBlock);

        // If the end of the physical device is insufficient for this block, jump to the start of the physical device
        if ((recordSectionCapacity - startOffset % recordSectionCapacity) < minSize) {
            startOffset = startOffset + recordSectionCapacity - startOffset % recordSectionCapacity;
        }

        // Not enough space for this block
        if (startOffset + minSize - trimOffset > recordSectionCapacity) {
            LOGGER.error("failed to allocate write offset as the ring buffer is full: startOffset: {}, minSize: {}, trimOffset: {}, recordSectionCapacity: {}",
                    startOffset, minSize, trimOffset, recordSectionCapacity);
            throw new OverCapacityException(String.format("failed to allocate write offset: ring buffer is full: startOffset: %d, minSize: %d, trimOffset: %d, recordSectionCapacity: %d",
                    startOffset, minSize, trimOffset, recordSectionCapacity));
        }

        long maxSize = upperLimit;
        // The size of the block should not be larger than writable size of the ring buffer
        // Let capacity=100, start=148, trim=49, then maxSize=100-148+49=1
        maxSize = Math.min(recordSectionCapacity - startOffset + trimOffset, maxSize);
        // Let capacity=100, start=198, trim=198, then maxSize=100-198%100=2
        // The size of the block should not be larger than the end of the physical device
        maxSize = Math.min(recordSectionCapacity - startOffset % recordSectionCapacity, maxSize);

        Block newBlock = new BlockImpl(startOffset, maxSize, blockSoftLimit);
        if (!previousBlock.isEmpty()) {
            // There are some records to be written in the previous block
            pendingBlocks.add(previousBlock);
        } else {
            // The previous block is empty, so it can be released directly
            previousBlock.release();
        }
        setCurrentBlockLocked(newBlock);
        return newBlock;
    }

    /**
     * Get the current block.
     * Note: this method is NOT thread safe, and it should be called with {@link #blockLock} locked.
     */
    public Block getCurrentBlockLocked() {
        // The current block is null only when no record has been written
        if (null == currentBlock) {
            currentBlock = nextBlock(windowCoreData.getWindowNextWriteOffset());
        }
        return currentBlock;
    }

    /**
     * Set the current block.
     * Note: this method is NOT thread safe, and it should be called with {@link #blockLock} locked.
     */
    private void setCurrentBlockLocked(Block block) {
        this.currentBlock = block;
    }

    /**
     * Get the start offset of the next block.
     */
    private long nextBlockStartOffset(Block block) {
        return block.startOffset() + WALUtil.alignLargeByBlockSize(block.size());
    }

    /**
     * Create a new block with the given start offset.
     * This method is only used when we don't know the maximum length of the new block.
     */
    private Block nextBlock(long startOffset) {
        // Trick: we cannot determine the maximum length of the block here, so we set it to 0 first.
        // When we try to write a record, this block will be found full, and then a new block will be created.
        return new BlockImpl(startOffset, 0, 0);
    }

    /**
     * Create a new block with the given previous block.
     * This method is only used when we don't know the maximum length of the new block.
     */
    private Block nextBlock(Block previousBlock) {
        return nextBlock(nextBlockStartOffset(previousBlock));
    }

    /**
     * Get the first block to be written. If there is no non-empty block, return null.
     */
    private Block pollBlock() {
        blockLock.lock();
        try {
            return pollBlockLocked();
        } finally {
            blockLock.unlock();
        }
    }

    /**
     * Get the first block to be written. If there is no non-empty block, return null.
     * Note: this method is NOT thread safe, and it should be called with {@link #blockLock} locked.
     */
    private Block pollBlockLocked() {
        Block pendingBlock = pendingBlocks.poll();
        if (null != pendingBlock) {
            writingBlocks.add(pendingBlock.startOffset());
            return pendingBlock;
        }

        Block currentBlock = getCurrentBlockLocked();
        if (currentBlock.isEmpty()) {
            // No record to be written
            return null;
        }

        Block newCurrentBlock = nextBlock(currentBlock);
        setCurrentBlockLocked(newCurrentBlock);
        writingBlocks.add(currentBlock.startOffset());
        return currentBlock;
    }

    /**
     * Calculate the start offset of the first block which has not been flushed yet.
     */
    private long calculateStartOffset(Block wroteBlock) {
        blockLock.lock();
        try {
            return calculateStartOffsetLocked(wroteBlock);
        } finally {
            blockLock.unlock();
        }
    }

    /**
     * Calculate the start offset of the first block which has not been flushed yet.
     * Note: this method is NOT thread safe, and it should be called with {@link #blockLock} locked.
     */
    private long calculateStartOffsetLocked(Block wroteBlock) {
        boolean removed = writingBlocks.remove(wroteBlock.startOffset());
        assert removed;
        if (writingBlocks.isEmpty()) {
            return getCurrentBlockLocked().startOffset();
        }
        return writingBlocks.first();
    }

    private void writeBlockData(Block block) throws IOException {
        // TODO: make this beautiful
        long position = WALUtil.recordOffsetToPosition(block.startOffset(), walChannel.capacity() - WAL_HEADER_TOTAL_CAPACITY, WAL_HEADER_TOTAL_CAPACITY);

        walChannel.write(block.data(), position);
    }

    private boolean makeWriteOffsetMatchWindow(final Block block) throws IOException {
        long newWindowEndOffset = block.startOffset() + block.size();
        // align to block size
        newWindowEndOffset = WALUtil.alignLargeByBlockSize(newWindowEndOffset);
        long windowStartOffset = windowCoreData.getWindowStartOffset();
        long windowMaxLength = windowCoreData.getWindowMaxLength();
        if (newWindowEndOffset > windowStartOffset + windowMaxLength) {
            long newWindowMaxLength = newWindowEndOffset - windowStartOffset + scaleUnit;
            if (newWindowMaxLength > upperLimit) {
                // exceed upper limit
                if (newWindowEndOffset - windowStartOffset >= upperLimit) {
                    // however, the new window length is still larger than upper limit, so we just set it to upper limit
                    newWindowMaxLength = upperLimit;
                } else {
                    // the new window length is bigger than upper limit, reject this write request
                    LOGGER.error("new windows size {} exceeds upper limit {}, reject this write request, window start offset: {}, new window end offset: {}",
                            newWindowMaxLength, upperLimit, windowStartOffset, newWindowEndOffset);
                    FutureUtil.completeExceptionally(block.futures(),
                            new OverCapacityException(String.format("new windows size exceeds upper limit %d", upperLimit)));
                    return false;
                }
            }
            windowCoreData.scaleOutWindow(walHeaderFlusher, newWindowMaxLength);
        }
        return true;
    }

    public interface WALHeaderFlusher {
        void flush(long windowMaxLength) throws IOException;
    }

    public static class RecordHeaderCoreData {
        private int magicCode0 = RECORD_HEADER_MAGIC_CODE;
        private int recordBodyLength1;
        private long recordBodyOffset2;
        private int recordBodyCRC3;
        private int recordHeaderCRC4;

        public static RecordHeaderCoreData unmarshal(ByteBuf byteBuf) {
            RecordHeaderCoreData recordHeaderCoreData = new RecordHeaderCoreData();
            byteBuf.markReaderIndex();
            recordHeaderCoreData.magicCode0 = byteBuf.readInt();
            recordHeaderCoreData.recordBodyLength1 = byteBuf.readInt();
            recordHeaderCoreData.recordBodyOffset2 = byteBuf.readLong();
            recordHeaderCoreData.recordBodyCRC3 = byteBuf.readInt();
            recordHeaderCoreData.recordHeaderCRC4 = byteBuf.readInt();
            byteBuf.resetReaderIndex();
            return recordHeaderCoreData;
        }

        public int getMagicCode() {
            return magicCode0;
        }

        public RecordHeaderCoreData setMagicCode(int magicCode) {
            this.magicCode0 = magicCode;
            return this;
        }

        public int getRecordBodyLength() {
            return recordBodyLength1;
        }

        public RecordHeaderCoreData setRecordBodyLength(int recordBodyLength) {
            this.recordBodyLength1 = recordBodyLength;
            return this;
        }

        public long getRecordBodyOffset() {
            return recordBodyOffset2;
        }

        public RecordHeaderCoreData setRecordBodyOffset(long recordBodyOffset) {
            this.recordBodyOffset2 = recordBodyOffset;
            return this;
        }

        public int getRecordBodyCRC() {
            return recordBodyCRC3;
        }

        public RecordHeaderCoreData setRecordBodyCRC(int recordBodyCRC) {
            this.recordBodyCRC3 = recordBodyCRC;
            return this;
        }

        public int getRecordHeaderCRC() {
            return recordHeaderCRC4;
        }

        @Override
        public String toString() {
            return "RecordHeaderCoreData{" +
                    "magicCode=" + magicCode0 +
                    ", recordBodyLength=" + recordBodyLength1 +
                    ", recordBodyOffset=" + recordBodyOffset2 +
                    ", recordBodyCRC=" + recordBodyCRC3 +
                    ", recordHeaderCRC=" + recordHeaderCRC4 +
                    '}';
        }

        private ByteBuf marshalHeaderExceptCRC() {
            ByteBuf buf = DirectByteBufAlloc.byteBuffer(RECORD_HEADER_SIZE);
            buf.writeInt(magicCode0);
            buf.writeInt(recordBodyLength1);
            buf.writeLong(recordBodyOffset2);
            buf.writeInt(recordBodyCRC3);
            return buf;
        }

        public ByteBuf marshal() {
            ByteBuf buf = marshalHeaderExceptCRC();
            buf.writeInt(WALUtil.crc32(buf, RECORD_HEADER_WITHOUT_CRC_SIZE));
            return buf;
        }
    }

    public static class WindowCoreData {
        private final Lock scaleOutLock = new ReentrantLock();
        private final AtomicLong windowMaxLength = new AtomicLong(0);
        /**
         * Next write offset of sliding window, always aligned to the {@link WALUtil#BLOCK_SIZE}.
         */
        private final AtomicLong windowNextWriteOffset = new AtomicLong(0);
        /**
         * Start offset of sliding window, always aligned to the {@link WALUtil#BLOCK_SIZE}.
         * The data before this offset has already been written to the disk.
         */
        private final AtomicLong windowStartOffset = new AtomicLong(0);

        public long getWindowMaxLength() {
            return windowMaxLength.get();
        }

        public void setWindowMaxLength(long windowMaxLength) {
            this.windowMaxLength.set(windowMaxLength);
        }

        public long getWindowNextWriteOffset() {
            return windowNextWriteOffset.get();
        }

        public void setWindowNextWriteOffset(long windowNextWriteOffset) {
            this.windowNextWriteOffset.set(windowNextWriteOffset);
        }

        public long getWindowStartOffset() {
            return windowStartOffset.get();
        }

        public void setWindowStartOffset(long windowStartOffset) {
            this.windowStartOffset.set(windowStartOffset);
        }

        public void updateWindowStartOffset(long offset) {
            this.windowStartOffset.accumulateAndGet(offset, Math::max);
        }

        public void scaleOutWindow(WALHeaderFlusher flusher, long newWindowMaxLength) throws IOException {
            boolean scaleWindowHappened = false;
            scaleOutLock.lock();
            try {
                if (newWindowMaxLength < getWindowMaxLength()) {
                    // Another thread has already scaled out the window.
                    return;
                }

                flusher.flush(newWindowMaxLength);
                setWindowMaxLength(newWindowMaxLength);
                scaleWindowHappened = true;
            } finally {
                scaleOutLock.unlock();
                if (scaleWindowHappened) {
                    LOGGER.info("window scale out to {}", newWindowMaxLength);
                } else {
                    LOGGER.debug("window already scale out, ignore");
                }
            }
        }
    }

    class WriteBlockProcessor implements Runnable {
        private final Block block;

        public WriteBlockProcessor(Block block) {
            this.block = block;
        }

        @Override
        public void run() {
            Block block = this.block;
            do {
                writeBlock(block);
                block = pollBlock();
            } while (block != null);
            semaphore.release();
        }

        private void writeBlock(Block block) {
            try {
                if (makeWriteOffsetMatchWindow(block)) {
                    writeBlockData(block);

                    // Update the start offset of the sliding window after finishing writing the record.
                    windowCoreData.updateWindowStartOffset(calculateStartOffset(block));

                    FutureUtil.complete(block.futures(), new AppendResult.CallbackResult() {
                        @Override
                        public long flushedOffset() {
                            return windowCoreData.getWindowStartOffset();
                        }

                        @Override
                        public String toString() {
                            return "CallbackResult{" + "flushedOffset=" + flushedOffset() + '}';
                        }
                    });
                }
            } catch (Exception e) {
                FutureUtil.completeExceptionally(block.futures(), e);
                LOGGER.error(String.format("failed to write record, offset: %s", block.startOffset()), e);
            } finally {
                block.release();
            }
        }
    }
}
