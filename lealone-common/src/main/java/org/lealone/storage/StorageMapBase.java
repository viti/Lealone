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
package org.lealone.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.lealone.common.exceptions.DbException;
import org.lealone.common.util.DataUtils;
import org.lealone.db.Session;
import org.lealone.db.value.ValueLong;
import org.lealone.net.NetEndpoint;
import org.lealone.storage.type.ObjectDataType;
import org.lealone.storage.type.StorageDataType;

public abstract class StorageMapBase<K, V> implements StorageMap<K, V> {

    protected final String name;
    protected final StorageDataType keyType;
    protected final StorageDataType valueType;
    protected final Storage storage;
    // TODO 考虑是否要使用总是递增的数字
    protected final AtomicLong maxKey = new AtomicLong(0);

    protected StorageMapBase(String name, StorageDataType keyType, StorageDataType valueType, Storage storage) {
        DataUtils.checkArgument(name != null, "The name may not be null");
        if (keyType == null) {
            keyType = new ObjectDataType();
        }
        if (valueType == null) {
            valueType = new ObjectDataType();
        }
        this.name = name;
        this.keyType = keyType;
        this.valueType = valueType;
        this.storage = storage;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public StorageDataType getKeyType() {
        return keyType;
    }

    @Override
    public StorageDataType getValueType() {
        return valueType;
    }

    @Override
    public Storage getStorage() {
        return storage;
    }

    @SuppressWarnings("unchecked")
    @Override
    public K append(V value) {
        K key = (K) ValueLong.get(maxKey.incrementAndGet());
        put(key, value);
        return key;
    }

    // 如果新key比lastKey大就更新lastKey
    // 允许多线程并发更新
    @Override
    public void setMaxKey(Object key) {
        if (key instanceof ValueLong) {
            long k = ((ValueLong) key).getLong();
            while (true) {
                long old = maxKey.get();
                if (k > old) {
                    if (maxKey.compareAndSet(old, k))
                        break;
                } else {
                    break;
                }
            }
        }
    }

    @Override
    public long getMaxKeyAsLong() {
        return maxKey.get();
    }

    @Override
    public long incrementAndGetMaxKeyAsLong() {
        return maxKey.incrementAndGet();
    }

    @Override
    public long getDiskSpaceUsed() {
        return 0;
    }

    @Override
    public long getMemorySpaceUsed() {
        return 0;
    }

    @Override
    public void transferTo(WritableByteChannel target, K firstKey, K lastKey) throws IOException {
        throw DbException.getUnsupportedException("transferTo");
    }

    @Override
    public void transferFrom(ReadableByteChannel src) throws IOException {
        throw DbException.getUnsupportedException("transferFrom");
    }

    @Override
    public void addLeafPage(PageKey pageKey, ByteBuffer page, boolean addPage) {
        throw DbException.getUnsupportedException("addLeafPage");
    }

    @Override
    public void removeLeafPage(PageKey pageKey) {
        throw DbException.getUnsupportedException("removeLeafPage");
    }

    @Override
    public LeafPageMovePlan prepareMoveLeafPage(LeafPageMovePlan leafPageMovePlan) {
        throw DbException.getUnsupportedException("prepareMoveLeafPage");
    }

    @Override
    public List<NetEndpoint> getReplicationEndpoints(Object key) {
        throw DbException.getUnsupportedException("getReplicationEndpoints");
    }

    @Override
    public Object replicationPut(Session session, Object key, Object value, StorageDataType valueType) {
        throw DbException.getUnsupportedException("put");
    }

    @Override
    public Object replicationGet(Session session, Object key) {
        throw DbException.getUnsupportedException("get");
    }

    @Override
    public Object replicationAppend(Session session, Object value, StorageDataType valueType) {
        throw DbException.getUnsupportedException("append");
    }

    @SuppressWarnings("unchecked")
    @Override
    public StorageMap<Object, Object> getRawMap() {
        return (StorageMap<Object, Object>) this;
    }

    @Override
    public void setRootPage(ByteBuffer buff) {
        throw DbException.getUnsupportedException("setRootPage");
    }
}
