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
package org.lealone.db;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.lealone.net.NetEndpoint;
import org.lealone.storage.Storage;
import org.lealone.storage.replication.ReplicationSession;

public interface IDatabase {

    int getId();

    String getShortName();

    void notifyRunModeChanged();

    Session createInternalSession();

    Session createInternalSession(boolean useSystemDatabase);

    String[] getHostIds();

    ReplicationSession createReplicationSession(Session session, Collection<NetEndpoint> replicationEndpoints);

    ReplicationSession createReplicationSession(Session session, Collection<NetEndpoint> replicationEndpoints,
            Boolean remote);

    NetEndpoint getEndpoint(String hostId);

    String getHostId(NetEndpoint endpoint);

    String getLocalHostId();

    List<NetEndpoint> getReplicationEndpoints(Set<NetEndpoint> oldReplicationEndpoints,
            Set<NetEndpoint> candidateEndpoints);

    boolean isShardingMode();

    Map<String, String> getReplicationProperties();

    Map<String, String> getEndpointAssignmentProperties();

    List<Storage> getStorages();

    Map<String, String> getParameters();

    RunMode getRunMode();

    boolean isStarting();

    String getCreateSQL();
}
