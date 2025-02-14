/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.automq.rocketmq.store.service;

import com.automq.rocketmq.store.api.StreamStore;
import com.automq.rocketmq.store.mock.MockStreamStore;
import com.automq.rocketmq.store.model.stream.SingleRecord;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StreamReclaimServiceTest {
    private static final long CONSUMER_GROUP_ID = 13;
    private static final long STREAM_ID = 1313;

    private StreamStore streamStore;
    private StreamReclaimService streamReclaimService;

    @BeforeEach
    public void setUp() throws Exception {
        streamStore = Mockito.spy(new MockStreamStore());
        streamStore.start();
        streamReclaimService = new StreamReclaimService(streamStore);
        streamReclaimService.start();
        streamStore.open(STREAM_ID, 0);
    }

    @AfterEach
    public void tearDown() throws Exception {
        streamReclaimService.shutdown();
        streamStore.shutdown();
    }

    @Test
    public void trim_stream_normal() {
        // 1. append 100 records
        for (int i = 0; i < 100; i++) {
            streamStore.append(STREAM_ID, buildRecord()).join();
        }
        assertEquals(0, streamStore.startOffset(STREAM_ID));
        assertEquals(100, streamStore.nextOffset(STREAM_ID));

        // 2. trim stream to new start offset: 10
        CompletableFuture<StreamReclaimService.StreamReclaimResult> taskCf =
            streamReclaimService.addReclaimTask(new StreamReclaimService.StreamReclaimTask(CompletableFuture.completedFuture(STREAM_ID), 10));
        StreamReclaimService.StreamReclaimResult result = taskCf.join();
        assertTrue(result.success());
        assertEquals(10, result.startOffset());
        assertEquals(10, streamStore.startOffset(STREAM_ID));

        // 3. trim stream to new start offset: 5
        taskCf = streamReclaimService.addReclaimTask(new StreamReclaimService.StreamReclaimTask(CompletableFuture.completedFuture(STREAM_ID), 5));
        result = taskCf.join();
        assertTrue(result.success());
        assertEquals(10, result.startOffset());
        assertEquals(10, streamStore.startOffset(STREAM_ID));

        // 4. trim stream to new start offset: 101
        taskCf = streamReclaimService.addReclaimTask(new StreamReclaimService.StreamReclaimTask(CompletableFuture.completedFuture(STREAM_ID), 101));
        result = taskCf.join();
        assertTrue(result.success());
        assertEquals(101, result.startOffset());
        assertEquals(101, streamStore.startOffset(STREAM_ID));
    }

    @Test
    public void trim_stream_fail() {
        // 1. append 10 records
        for (int i = 0; i < 10; i++) {
            streamStore.append(STREAM_ID, buildRecord()).join();
        }
        assertEquals(0, streamStore.startOffset(STREAM_ID));
        assertEquals(10, streamStore.nextOffset(STREAM_ID));

        Mockito.doAnswer(ink -> {
            throw new RuntimeException("test");
        }).when(streamStore).trim(STREAM_ID, 5);

        // 2. trim stream to new start offset: 5
        CompletableFuture<StreamReclaimService.StreamReclaimResult> taskCf =
            streamReclaimService.addReclaimTask(new StreamReclaimService.StreamReclaimTask(CompletableFuture.completedFuture(STREAM_ID), 5));
        try {
            taskCf.join();
        } catch (Exception e) {
            assertTrue(e instanceof CompletionException);
            assertTrue(e.getCause() instanceof RuntimeException);
        }
        assertTrue(taskCf.isCompletedExceptionally());
        assertEquals(0, streamStore.startOffset(STREAM_ID));
        assertEquals(10, streamStore.nextOffset(STREAM_ID));
    }

    private SingleRecord buildRecord() {
        ByteBuffer buffer = ByteBuffer.allocate(4);
        buffer.putInt(13);
        buffer.flip();
        return new SingleRecord(buffer);
    }

}
