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

import java.util.Map;
import java.util.Set;

import org.lealone.common.exceptions.DbException;
import org.lealone.db.RunMode;
import org.lealone.storage.type.StorageDataType;

public interface Storage {

    <K, V> StorageMap<K, V> openMap(String name, StorageDataType keyType, StorageDataType valueType,
            Map<String, String> parameters);

    void closeMap(String name);

    boolean hasMap(String name);

    StorageMap<?, ?> getMap(String name);

    Set<String> getMapNames();

    String nextTemporaryMapName();

    String getStoragePath();

    boolean isInMemory();

    long getDiskSpaceUsed();

    long getMemorySpaceUsed();

    void save();

    void drop();

    void backupTo(String fileName);

    void close();

    void closeImmediately();

    boolean isClosed();

    void registerEventListener(StorageEventListener listener);

    void unregisterEventListener(StorageEventListener listener);

    default void replicate(Object dbObject, String[] newReplicationEndpoints, RunMode runMode) {
        throw DbException.getUnsupportedException("replicate");
    }

    default void sharding(Object dbObject, String[] oldEndpoints, String[] newEndpoints, RunMode runMode) {
        throw DbException.getUnsupportedException("sharding");
    }

    default void scaleIn(Object dbObject, RunMode oldRunMode, RunMode newRunMode, String[] oldEndpoints,
            String[] newEndpoints) {
        throw DbException.getUnsupportedException("scaleIn");
    }
}
