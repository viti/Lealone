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

import java.lang.management.ManagementFactory;
import java.net.UnknownHostException;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.lealone.net.NetEndpoint;
import org.lealone.p2p.config.ConfigDescriptor;
import org.lealone.p2p.util.Utils;

public class EndpointSnitchInfo implements EndpointSnitchInfoMBean {
    public static void create() {
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        try {
            mbs.registerMBean(new EndpointSnitchInfo(), new ObjectName(Utils.getJmxObjectName("EndpointSnitchInfo")));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String getDatacenter(String host) throws UnknownHostException {
        return ConfigDescriptor.getEndpointSnitch().getDatacenter(NetEndpoint.getByName(host));
    }

    @Override
    public String getRack(String host) throws UnknownHostException {
        return ConfigDescriptor.getEndpointSnitch().getRack(NetEndpoint.getByName(host));
    }

    @Override
    public String getSnitchName() {
        return ConfigDescriptor.getEndpointSnitch().getClass().getName();
    }
}
