/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lealone.server;

import java.util.Map;

public class TcpServerEngine extends ProtocolServerEngineBase {

    public static final String NAME = "TCP";
    private final TcpServer tcpServer = new TcpServer();

    public TcpServerEngine() {
        super(NAME);
    }

    @Override
    public ProtocolServer getProtocolServer() {
        return tcpServer;
    }

    @Override
    public void init(Map<String, String> config) {
        tcpServer.init(config);
    }

    @Override
    public void close() {
        tcpServer.stop();
    }

    @Override
    protected ProtocolServer getProtocolServer(int port) {
        return tcpServer;
    }

}
