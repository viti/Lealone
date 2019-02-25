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
package org.lealone.p2p.locator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.lealone.common.exceptions.ConfigException;
import org.lealone.net.NetEndpoint;
import org.lealone.p2p.config.ConfigDescriptor;

public class LocalStrategy extends AbstractReplicationStrategy {

    public LocalStrategy(String dbName, IEndpointSnitch snitch, Map<String, String> configOptions) {
        super(dbName, snitch, configOptions);
    }

    @Override
    public int getReplicationFactor() {
        return 1;
    }

    @Override
    public void validateOptions() throws ConfigException {
    }

    @Override
    public Collection<String> recognizedOptions() {
        // LocalStrategy doesn't expect any options.
        return Collections.<String> emptySet();
    }

    /**
     * 覆盖默认实现，默认实现里要拷贝TopologyMetaData
     */
    @Override
    public List<NetEndpoint> getReplicationEndpoints(TopologyMetaData metaData,
            Set<NetEndpoint> oldReplicationEndpoints, Set<NetEndpoint> candidateEndpoints,
            boolean includeOldReplicationEndpoints) {
        ArrayList<NetEndpoint> list = new ArrayList<NetEndpoint>(1);
        list.add(ConfigDescriptor.getLocalEndpoint());
        return list;
    }

    @Override
    public List<NetEndpoint> calculateReplicationEndpoints(TopologyMetaData metaData,
            Set<NetEndpoint> oldReplicationEndpoints, Set<NetEndpoint> candidateEndpoints,
            boolean includeOldReplicationEndpoints) {
        return Collections.singletonList(ConfigDescriptor.getLocalEndpoint());
    }
}
