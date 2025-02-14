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

package com.automq.rocketmq.store.model.message;

import com.automq.rocketmq.common.model.FlatMessageExt;
import java.util.List;

public interface Filter {
    Filter DEFAULT_FILTER = new Filter() {
        @Override
        public FilterType type() {
            return FilterType.NONE;
        }

        @Override
        public String expression() {
            return "";
        }

        @Override
        public List<FlatMessageExt> doFilter(List<FlatMessageExt> messageList) {
            return messageList;
        }

        @Override
        public boolean doFilter(String tag) {
            return true;
        }
    };

    FilterType type();

    default boolean needApply() {
        return type() != FilterType.NONE;
    }

    String expression();

    List<FlatMessageExt> doFilter(List<FlatMessageExt> messageList);

    boolean doFilter(String tag);
}
