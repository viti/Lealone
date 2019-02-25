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
package org.lealone.p2p.gms;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

class GossipDigestSerializationHelper {
    private GossipDigestSerializationHelper() {
    }

    static void serialize(List<GossipDigest> gDigestList, DataOutput out, int version) throws IOException {
        out.writeInt(gDigestList.size());
        for (GossipDigest gDigest : gDigestList)
            GossipDigest.serializer.serialize(gDigest, out, version);
    }

    static List<GossipDigest> deserialize(DataInput in, int version) throws IOException {
        int size = in.readInt();
        List<GossipDigest> gDigests = new ArrayList<GossipDigest>(size);
        for (int i = 0; i < size; ++i)
            gDigests.add(GossipDigest.serializer.deserialize(in, version));
        return gDigests;
    }
}