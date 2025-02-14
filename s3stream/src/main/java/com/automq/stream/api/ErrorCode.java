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

package com.automq.stream.api;

public class ErrorCode {
    public static final short UNEXPECTED = 1;
    public static final short OFFSET_OUT_OF_RANGE_BOUNDS = 1463;
    public static final short STREAM_ALREADY_CLOSED = 1478;
    public static final short EXPIRED_STREAM_EPOCH = 1489;

    public static final short STREAM_NOT_EXIST = 1490;

    public static final short STREAM_NOT_CLOSED = 1491;
}
