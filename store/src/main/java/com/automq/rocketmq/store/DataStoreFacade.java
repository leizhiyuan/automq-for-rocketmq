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

package com.automq.rocketmq.store;

import com.automq.rocketmq.common.api.DataStore;
import com.automq.rocketmq.store.api.LogicQueueManager;
import com.automq.rocketmq.store.api.S3ObjectOperator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class DataStoreFacade implements DataStore {

    private final S3ObjectOperator s3ObjectOperator;

    private final LogicQueueManager logicQueueManager;

    public DataStoreFacade(S3ObjectOperator s3ObjectOperator, LogicQueueManager logicQueueManager) {
        this.s3ObjectOperator = s3ObjectOperator;
        this.logicQueueManager = logicQueueManager;
    }

    @Override
    public CompletableFuture<Void> closeQueue(long topicId, int queueId) {
        return logicQueueManager.close(topicId, queueId);
    }

    @Override
    public CompletableFuture<List<Long>> batchDeleteS3Objects(List<Long> objectIds) {
        return s3ObjectOperator.delete(objectIds);
    }
}
