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

package com.automq.rocketmq.store.queue;

import java.io.IOException;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class AckCommitterTest {

    @Test
    public void testGetAckBitmapBuffer() throws IOException {
        DefaultLogicQueueStateMachine.AckCommitter ackCommitter = new DefaultLogicQueueStateMachine.AckCommitter(100,
            System.out::println);
        ackCommitter.commitAck(102);
        ByteBuffer buffer = ackCommitter.getAckBitmapBuffer();
        Assertions.assertEquals(0, buffer.position());
        Assertions.assertTrue(buffer.limit() > 0);
    }
}
