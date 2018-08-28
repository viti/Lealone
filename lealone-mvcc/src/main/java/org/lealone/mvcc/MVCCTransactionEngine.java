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
package org.lealone.mvcc;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.lealone.common.exceptions.DbException;
import org.lealone.common.util.DataUtils;
import org.lealone.common.util.ShutdownHookUtils;
import org.lealone.db.DataBuffer;
import org.lealone.db.value.ValueString;
import org.lealone.mvcc.MVCCTransaction.LogRecord;
import org.lealone.mvcc.log.LogSyncService;
import org.lealone.mvcc.log.RedoLog;
import org.lealone.mvcc.log.RedoLogRecord;
import org.lealone.storage.StorageMap;
import org.lealone.storage.type.StorageDataType;
import org.lealone.transaction.TransactionEngineBase;
import org.lealone.transaction.TransactionMap;

public class MVCCTransactionEngine extends TransactionEngineBase {

    private static final String NAME = "MVCC";
    private static final int DEFAULT_MAP_CACHE_SIZE = 32 * 1024 * 1024; // 32M
    private static final int DEFAULT_MAP_SAVE_PERIOD = 1 * 60 * 60 * 1000; // 1小时

    private int mapCacheSize = DEFAULT_MAP_CACHE_SIZE;
    private int mapSavePeriod = DEFAULT_MAP_SAVE_PERIOD;
    private StorageMapSaveService storageMapSaveService;

    // key: mapName
    private final ConcurrentHashMap<String, StorageMap<Object, TransactionalValue>> maps = new ConcurrentHashMap<>();
    // key: mapName, value: memory size
    private final ConcurrentHashMap<String, Integer> estimatedMemory = new ConcurrentHashMap<>();

    private final AtomicLong lastTransactionId = new AtomicLong();
    // key: mapName, value: map key/value ByteBuffer list
    private final HashMap<String, ArrayList<ByteBuffer>> pendingRedoLog = new HashMap<>();
    // key: mapName
    private final ConcurrentHashMap<String, TransactionMap<?, ?>> tmaps = new ConcurrentHashMap<>();

    private RedoLog redoLog;
    private LogSyncService logSyncService;

    private boolean init;

    // key: transactionId
    public final ConcurrentSkipListMap<Long, MVCCTransaction> currentTransactions = new ConcurrentSkipListMap<>();

    public MVCCTransactionEngine() {
        super(NAME);
    }

    public MVCCTransactionEngine(String name) {
        super(name);
    }

    StorageMap<Object, TransactionalValue> getMap(String mapName) {
        return maps.get(mapName);
    }

    public void addMap(StorageMap<Object, TransactionalValue> map) {
        estimatedMemory.put(map.getName(), 0);
        maps.put(map.getName(), map);
    }

    void removeMap(String mapName) {
        estimatedMemory.remove(mapName);
        maps.remove(mapName);
        RedoLogRecord r = new RedoLogRecord(mapName);
        redoLog.addRedoLogRecord(r);
        logSyncService.maybeWaitForSync(r);
    }

    private class StorageMapSaveService extends Thread {
        private volatile boolean isClosed;
        private volatile long lastSavedAt = System.currentTimeMillis();
        private final Semaphore semaphore = new Semaphore(1);
        private final int sleep;

        StorageMapSaveService(int sleep) {
            super("StorageMapSaveService");
            this.sleep = sleep;
            ShutdownHookUtils.addShutdownHook(this, () -> {
                StorageMapSaveService.this.close();
                try {
                    StorageMapSaveService.this.join();
                } catch (InterruptedException e) {
                }
            });
        }

        void close() {
            if (!isClosed) {
                isClosed = true;
                semaphore.release();
            }
        }

        @Override
        public void run() {
            while (!isClosed) {
                try {
                    semaphore.tryAcquire(sleep, TimeUnit.MILLISECONDS);
                    semaphore.drainPermits();
                } catch (InterruptedException e) {
                    throw new AssertionError();
                }

                long now = System.currentTimeMillis();
                boolean writeCheckpoint = false;
                for (Entry<String, Integer> e : estimatedMemory.entrySet()) {
                    if (isClosed || e.getValue() > mapCacheSize || lastSavedAt + mapSavePeriod > now) {
                        maps.get(e.getKey()).save();
                        writeCheckpoint = true;
                    }
                }
                if (lastSavedAt + mapSavePeriod > now)
                    lastSavedAt = now;

                if (writeCheckpoint) {
                    redoLog.writeCheckpoint();
                }
            }
        }
    }

    @Override
    public synchronized void init(Map<String, String> config) {
        if (init)
            return;
        init = true;

        String v = config.get("map_cache_size_in_mb");
        if (v != null)
            mapCacheSize = Integer.parseInt(v) * 1024 * 1024;

        v = config.get("map_save_period");
        if (v != null)
            mapSavePeriod = Integer.parseInt(v);

        int sleep = 1 * 60 * 1000;// 1分钟
        v = config.get("map_save_service_sleep_interval");
        if (v != null)
            sleep = Integer.parseInt(v);

        if (mapSavePeriod < sleep)
            sleep = mapSavePeriod;

        redoLog = new RedoLog(config);
        logSyncService = redoLog.getLogSyncService();
        initPendingRedoLog();

        // 调用完initPendingRedoLog后再启动logSyncService
        logSyncService.start();
        storageMapSaveService = new StorageMapSaveService(sleep);
        storageMapSaveService.start();
    }

    private void initPendingRedoLog() {
        long lastTransactionId = 0;
        for (RedoLogRecord r : redoLog.getAndResetRedoLogRecords()) {
            if (r.transactionId != null && r.transactionId > lastTransactionId) {
                lastTransactionId = r.transactionId;
            }
            if (r.droppedMap != null) {
                ArrayList<ByteBuffer> logs = pendingRedoLog.get(r.droppedMap);
                if (logs != null) {
                    logs = new ArrayList<>();
                    pendingRedoLog.put(r.droppedMap, logs);
                }
            } else {
                ByteBuffer buff = r.values;
                if (buff == null)
                    continue; // TODO 消除为NULL的可能
                while (buff.hasRemaining()) {
                    String mapName = ValueString.type.read(buff);

                    ArrayList<ByteBuffer> logs = pendingRedoLog.get(mapName);
                    if (logs == null) {
                        logs = new ArrayList<>();
                        pendingRedoLog.put(mapName, logs);
                    }
                    int len = buff.getInt();
                    byte[] keyValue = new byte[len];
                    buff.get(keyValue);
                    logs.add(ByteBuffer.wrap(keyValue));
                }
            }
        }
        this.lastTransactionId.set(lastTransactionId);
    }

    @SuppressWarnings("unchecked")
    public <K> void redo(StorageMap<K, TransactionalValue> map) {
        ArrayList<ByteBuffer> logs = pendingRedoLog.remove(map.getName());
        if (logs != null && !logs.isEmpty()) {
            K key;
            Object value;
            StorageDataType kt = map.getKeyType();
            StorageDataType vt = ((TransactionalValueType) map.getValueType()).valueType;
            for (ByteBuffer log : logs) {
                key = (K) kt.read(log);
                if (log.get() == 0)
                    map.remove(key);
                else {
                    value = vt.read(log);
                    map.put(key, new TransactionalValue(value));
                }
            }
        }
    }

    @Override
    public MVCCTransaction beginTransaction(boolean autoCommit, boolean isShardingMode) {
        if (!init) {
            throw DataUtils.newIllegalStateException(DataUtils.ERROR_TRANSACTION_ILLEGAL_STATE, "Not initialized");
        }
        long tid = getTransactionId(autoCommit, isShardingMode);
        MVCCTransaction t = createTransaction(tid);
        t.setAutoCommit(autoCommit);
        currentTransactions.put(tid, t);
        return t;
    }

    protected MVCCTransaction createTransaction(long tid) {
        return new MVCCTransaction(this, tid);
    }

    @Override
    public void close() {
        redoLog.close();
        if (storageMapSaveService != null) {
            storageMapSaveService.close();
            try {
                storageMapSaveService.join();
            } catch (InterruptedException e) {
            }
        }
    }

    private long getTransactionId(boolean autoCommit, boolean isShardingMode) {
        // 分布式事务使用奇数的事务ID
        if (!autoCommit && isShardingMode) {
            return nextOddTransactionId();
        }
        return nextEvenTransactionId();
    }

    public long nextOddTransactionId() {
        return nextTransactionId(false);
    }

    private long nextEvenTransactionId() {
        return nextTransactionId(true);
    }

    private long nextTransactionId(boolean isEven) {
        long oldLast;
        long last;
        int delta;
        do {
            oldLast = lastTransactionId.get();
            last = oldLast;
            if (last % 2 == 0)
                delta = isEven ? 2 : 1;
            else
                delta = isEven ? 1 : 2;

            last += delta;
        } while (!lastTransactionId.compareAndSet(oldLast, last));
        return last;
    }

    void prepareCommit(MVCCTransaction t, RedoLogRecord r) {
        // 事务没有进行任何操作时不用同步日志
        if (r != null) {
            // 先写redoLog
            redoLog.addRedoLogRecord(r);
        }
        logSyncService.prepareCommit(t);
    }

    void commit(MVCCTransaction t) {
        commitFinal(t.transactionId);
        if (t.getSession() != null && t.getSession().getRunnable() != null) {
            try {
                t.getSession().getRunnable().run();
            } catch (Exception e) {
                throw DbException.convert(e);
            }
        }
    }

    public void commit(MVCCTransaction t, RedoLogRecord r) {
        if (r != null) { // 事务没有进行任何操作时不用同步日志
            // 先写redoLog
            redoLog.addRedoLogRecord(r);
            logSyncService.maybeWaitForSync(r);
        }
        // 分布式事务推迟提交
        if (t.isLocal()) {
            commitFinal(t.transactionId);
        }
    }

    protected void commitFinal(long tid) {
        // 避免并发提交(TransactionValidator线程和其他读写线程都有可能在检查到分布式事务有效后帮助提交最终事务)
        MVCCTransaction t = currentTransactions.remove(tid);
        if (t == null)
            return;
        LinkedList<LogRecord> logRecords = t.logRecords;
        StorageMap<Object, TransactionalValue> map;
        for (LogRecord r : logRecords) {
            map = getMap(r.mapName);
            if (map == null) {
                // map was later removed
            } else {
                TransactionalValue value = map.get(r.key);
                if (value == null) {
                    // nothing to do
                } else if (value.value == null) {
                    // remove the value
                    map.remove(r.key);
                } else {
                    map.put(r.key, new TransactionalValue(value.value));
                }
            }
        }

        t.endTransaction();
    }

    public RedoLogRecord createRedoLogRecord(MVCCTransaction t) {
        if (t.logRecords.isEmpty())
            return null;
        try (DataBuffer writeBuffer = DataBuffer.create()) {
            String mapName;
            TransactionalValue value;
            StorageMap<?, ?> map;
            int lastPosition = 0, keyValueStart, memory;

            for (LogRecord r : t.logRecords) {
                mapName = r.mapName;
                value = r.newValue;
                map = maps.get(mapName);

                // 有可能在执行DROP DATABASE时删除了
                if (map == null) {
                    continue;
                }

                ValueString.type.write(writeBuffer, mapName);
                keyValueStart = writeBuffer.position();
                writeBuffer.putInt(0);

                map.getKeyType().write(writeBuffer, r.key);
                if (value.value == null)
                    writeBuffer.put((byte) 0);
                else {
                    writeBuffer.put((byte) 1);
                    ((TransactionalValueType) map.getValueType()).valueType.write(writeBuffer, value.value);
                }

                writeBuffer.putInt(keyValueStart, writeBuffer.position() - keyValueStart - 4);
                memory = estimatedMemory.get(mapName);
                memory += writeBuffer.position() - lastPosition;
                lastPosition = writeBuffer.position();
                estimatedMemory.put(mapName, memory);
            }

            ByteBuffer buffer = writeBuffer.getAndFlipBuffer();
            ByteBuffer values = ByteBuffer.allocateDirect(buffer.limit());
            values.put(buffer);
            values.flip();
            return new RedoLogRecord(t.transactionId, values);
        }
    }

    @Override
    public boolean supportsMVCC() {
        return true;
    }

    @Override
    public boolean validateTransaction(String localTransactionName) {
        return false;
    }

    @Override
    public void addTransactionMap(TransactionMap<?, ?> map) {
        tmaps.put(map.getName(), map);
    }

    @Override
    public TransactionMap<?, ?> getTransactionMap(String name) {
        return tmaps.get(name);
    }

    @Override
    public void removeTransactionMap(String name) {
        removeMap(name);
        tmaps.remove(name);
    }
}
