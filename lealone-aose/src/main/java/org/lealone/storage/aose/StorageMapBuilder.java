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
package org.lealone.storage.aose;

import java.util.Map;

import org.lealone.storage.StorageMap;
import org.lealone.storage.type.StorageDataType;

public abstract class StorageMapBuilder<M extends StorageMap<K, V>, K, V> {
    protected String name;
    protected StorageDataType keyType;
    protected StorageDataType valueType;
    protected Map<String, Object> config;
    protected AOStorage aoStorage;

    public StorageMapBuilder<M, K, V> name(String name) {
        this.name = name;
        return this;
    }

    public StorageMapBuilder<M, K, V> keyType(StorageDataType keyType) {
        this.keyType = keyType;
        return this;
    }

    public StorageMapBuilder<M, K, V> valueType(StorageDataType valueType) {
        this.valueType = valueType;
        return this;
    }

    public StorageMapBuilder<M, K, V> config(Map<String, Object> config) {
        this.config = config;
        return this;
    }

    public StorageMapBuilder<M, K, V> aoStorage(AOStorage aoStorage) {
        this.aoStorage = aoStorage;
        return this;
    }

    public abstract M openMap();
}
