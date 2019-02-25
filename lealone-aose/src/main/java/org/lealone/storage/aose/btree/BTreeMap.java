/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0, and the
 * EPL 1.0 (http://h2database.com/html/license.html). Initial Developer: H2
 * Group
 */
package org.lealone.storage.aose.btree;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.lealone.common.exceptions.DbException;
import org.lealone.common.util.DataUtils;
import org.lealone.common.util.StringUtils;
import org.lealone.db.DataBuffer;
import org.lealone.db.IDatabase;
import org.lealone.db.RunMode;
import org.lealone.db.Session;
import org.lealone.net.NetEndpoint;
import org.lealone.storage.IterationParameters;
import org.lealone.storage.LeafPageMovePlan;
import org.lealone.storage.PageKey;
import org.lealone.storage.StorageCommand;
import org.lealone.storage.StorageMapBase;
import org.lealone.storage.StorageMapCursor;
import org.lealone.storage.aose.AOStorage;
import org.lealone.storage.aose.AOStorageService;
import org.lealone.storage.aose.StorageMapBuilder;
import org.lealone.storage.replication.ReplicationSession;
import org.lealone.storage.type.StorageDataType;

/**
 * A read optimization BTree stored map.
 * <p>
 * Read operations can happen concurrently with all other operations, without
 * risk of corruption.
 * <p>
 * Write operations first read the relevant area from disk to memory
 * concurrently, and only then modify the data. The in-memory part of write
 * operations is synchronized. For scalable concurrent in-memory write
 * operations, the map should be split into multiple smaller sub-maps that are
 * then synchronized independently.
 * 
 * @param <K> the key class
 * @param <V> the value class
 * 
 * @author H2 Group
 * @author zhh
 */
public class BTreeMap<K, V> extends StorageMapBase<K, V> {

    /**
     * A builder for this class.
     */
    public static class Builder<K, V> extends StorageMapBuilder<BTreeMap<K, V>, K, V> {
        @Override
        public BTreeMap<K, V> openMap() {
            return new BTreeMap<>(name, keyType, valueType, config, aoStorage);
        }
    }

    protected final boolean readOnly;
    protected final boolean isShardingMode;

    protected final Map<String, Object> config;
    protected final BTreeStorage btreeStorage;
    protected PageStorageMode pageStorageMode = PageStorageMode.ROW_STORAGE;
    protected IDatabase db;

    /**
     * The current root page (may not be null).
     */
    protected volatile BTreePage root;

    private RunMode runMode;
    private String[] oldEndpoints;

    @SuppressWarnings("unchecked")
    protected BTreeMap(String name, StorageDataType keyType, StorageDataType valueType, Map<String, Object> config,
            AOStorage aoStorage) {
        super(name, keyType, valueType, aoStorage);
        DataUtils.checkArgument(config != null, "The config may not be null");

        this.readOnly = config.containsKey("readOnly");
        boolean isShardingMode = false;
        if (config.containsKey("isShardingMode"))
            isShardingMode = Boolean.parseBoolean(config.get("isShardingMode").toString());
        this.config = config;
        this.db = (IDatabase) config.get("db");

        Object mode = config.get("pageStorageMode");
        if (mode != null) {
            pageStorageMode = PageStorageMode.valueOf(mode.toString());
        }

        btreeStorage = new BTreeStorage((BTreeMap<Object, Object>) this);

        if (btreeStorage.lastChunk != null) {
            root = btreeStorage.readPage(btreeStorage.lastChunk.rootPagePos);
            setMaxKey(lastKey());
        } else {
            if (isShardingMode) {
                String initReplicationEndpoints = (String) config.get("initReplicationEndpoints");
                DataUtils.checkArgument(initReplicationEndpoints != null,
                        "The initReplicationEndpoints may not be null");
                String[] replicationEndpoints = StringUtils.arraySplit(initReplicationEndpoints, '&');
                if (containsLocalEndpoint(replicationEndpoints)) {
                    root = BTreeLeafPage.createEmpty(this);
                } else {
                    root = new BTreeRemotePage(this);
                }
                root.setReplicationHostIds(Arrays.asList(replicationEndpoints));
                btreeStorage.addHostIds(replicationEndpoints);
                // 强制把replicationHostIds持久化
                btreeStorage.forceSave();
            } else {
                isShardingMode = false;
                root = BTreeLeafPage.createEmpty(this);
            }
        }

        this.isShardingMode = isShardingMode;
    }

    private boolean containsLocalEndpoint(String[] replicationEndpoints) {
        NetEndpoint local = NetEndpoint.getLocalTcpEndpoint();
        for (String e : replicationEndpoints) {
            if (local.equals(NetEndpoint.createTCP(e)))
                return true;
        }
        return false;
    }

    @Override
    public V get(K key) {
        return get(key, true);
    }

    @SuppressWarnings("unchecked")
    public V get(K key, boolean allColumns) {
        return (V) binarySearch(root, key, allColumns);
    }

    public V get(K key, int columnIndex) {
        return get(key, new int[] { columnIndex });
    }

    @Override
    @SuppressWarnings("unchecked")
    public V get(K key, int[] columnIndexes) {
        return (V) binarySearch(root, key, columnIndexes);
    }

    /**
     * Get the value for the given key, or null if not found.
     * 
     * @param p the page
     * @param key the key
     * @return the value or null
     */
    protected Object binarySearch(BTreePage p, Object key, boolean allColumns) {
        while (true) {
            int index = p.binarySearch(key);
            if (p.isLeaf()) {
                return index >= 0 ? p.getValue(index, allColumns) : null;
            } else {
                if (index < 0) {
                    index = -index - 1;
                } else {
                    index++;
                }
                p = p.getChildPage(index);
            }
        }
        // 递归版本
        // int index = p.binarySearch(key);
        // if (p.isNode()) {
        // if (index < 0) {
        // index = -index - 1;
        // } else {
        // index++;
        // }
        // p = p.getChildPage(index);
        // return binarySearch(p, key);
        // }
        // return index >= 0 ? p.getValue(index) : null;
    }

    protected Object binarySearch(BTreePage p, Object key, int[] columnIndexes) {
        while (true) {
            int index = p.binarySearch(key);
            if (p.isLeaf()) {
                return index >= 0 ? p.getValue(index, columnIndexes) : null;
            } else {
                if (index < 0) {
                    index = -index - 1;
                } else {
                    index++;
                }
                p = p.getChildPage(index);
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized V put(K key, V value) {
        DataUtils.checkArgument(value != null, "The value may not be null");

        beforeWrite();
        BTreePage p = root.copy();

        boolean split = false;
        if (p.needSplit()) {
            p = splitRoot(p);
            split = true;
        }

        Object result = put(p, key, value);
        if (split && isShardingMode && root.isLeaf()) {
            PageKey pk = new PageKey(p.getKey(0), false); // 移动右边的Page
            moveLeafPageLazy(pk);
        }

        newRoot(p);
        return (V) result;
    }

    /**
     * This method is called before writing to the map. 
     * The default implementation checks whether writing is allowed.
     * 
     * @throws UnsupportedOperationException if the map is read-only.
     */
    protected void beforeWrite() {
        if (btreeStorage.isClosed()) {
            throw DataUtils.newIllegalStateException(DataUtils.ERROR_CLOSED, "This map is closed");
        }
        if (readOnly) {
            throw DataUtils.newUnsupportedOperationException("This map is read-only");
        }
    }

    /**
     * Split the root page.
     * 
     * @param p the page
     * @return the new root page
     */
    private BTreePage splitRoot(BTreePage p) {
        long totalCount = p.getTotalCount();
        int at = p.getKeyCount() / 2;
        Object k = p.getKey(at);
        BTreePage rightChildPage = p.split(at);
        Object[] keys = { k };
        PageReference[] children = { new PageReference(p, p.getPos(), p.getTotalCount(), k, true),
                new PageReference(rightChildPage, rightChildPage.getPos(), rightChildPage.getTotalCount(), k, false) };
        p = BTreePage.create(this, keys, null, children, totalCount, 0);
        return p;
    }

    /**
     * Add or update a key-value pair.
     * 
     * @param p the page
     * @param key the key (may not be null)
     * @param value the value (may not be null)
     * @return the old value, or null
     */
    private Object put(BTreePage p, Object key, Object value) {
        // 本地后台批量put时(比如通过BufferedMap执行)，可能会发生leafPage切割，
        // 这时复制节点就发生改变了，需要重定向到新的复制节点
        // 比如下面这样的场景就会发生:
        // AOStorageService执行完merge后，正准备执行page move，此时又有新数据写入BufferedMap，
        // 当下次merge执行到put时，page所在节点就可能不是当前节点了
        if (p.getLeafPageMovePlan() != null) {
            return putRemote(p, key, value);
        } else {
            return putLocal(p, key, value);
        }
    }

    private String getLocalHostId() {
        return db.getLocalHostId();
    }

    private Object putRemote(BTreePage p, Object key, Object value) {
        if (p.getLeafPageMovePlan().moverHostId.equals(getLocalHostId())) {
            int size = p.getLeafPageMovePlan().replicationEndpoints.size();
            List<NetEndpoint> replicationEndpoints = new ArrayList<>(size);
            replicationEndpoints.addAll(p.getLeafPageMovePlan().replicationEndpoints);
            boolean containsLocalEndpoint = replicationEndpoints.remove(getLocalEndpoint());
            Object returnValue = null;
            ReplicationSession rs = db.createReplicationSession(db.createInternalSession(), replicationEndpoints);
            try (DataBuffer k = DataBuffer.create();
                    DataBuffer v = DataBuffer.create();
                    StorageCommand c = rs.createStorageCommand()) {
                ByteBuffer keyBuffer = k.write(keyType, key);
                ByteBuffer valueBuffer = v.write(valueType, value);
                byte[] oldValue = (byte[]) c.executePut(null, getName(), keyBuffer, valueBuffer, true);
                if (oldValue != null) {
                    returnValue = valueType.read(ByteBuffer.wrap(oldValue));
                }
            }
            // 如果新的复制节点中还包含本地节点，那么还需要put到本地节点中
            if (containsLocalEndpoint) {
                return putLocal(p, key, value);
            } else {
                return returnValue;
            }
        } else {
            return null; // 不是由当前节点移动的，那么put操作就可以忽略了
        }
    }

    private Object putLocal(BTreePage p, Object key, Object value) {
        int index = p.binarySearch(key);
        if (p.isLeaf()) {
            if (index < 0) {
                index = -index - 1;
                p.insertLeaf(index, key, value);
                setMaxKey(key);
                return null;
            }
            return p.setValue(index, value);
        }
        // p is a node
        if (index < 0) {
            index = -index - 1;
        } else {
            index++;
        }
        BTreePage c = p.getChildPage(index).copy();
        if (c.needSplit()) {
            boolean isLeaf = c.isLeaf();
            // split on the way down
            int at = c.getKeyCount() / 2;
            Object k = c.getKey(at);
            BTreePage rightChildPage = c.split(at);
            p.setChild(index, rightChildPage);
            p.insertNode(index, k, c);
            // now we are not sure where to add
            Object result = put(p, key, value);
            if (isLeaf && isShardingMode) {
                PageKey pk = new PageKey(k, false); // 移动右边的Page
                moveLeafPageLazy(pk);
            }
            return result;
        }
        Object result = put(c, key, value);
        p.setChild(index, c);
        return result;
    }

    private Set<NetEndpoint> getCandidateEndpoints() {
        return getCandidateEndpoints(db, db.getHostIds());
    }

    static Set<NetEndpoint> getCandidateEndpoints(IDatabase db, String[] hostIds) {
        Set<NetEndpoint> candidateEndpoints = new HashSet<>(hostIds.length);
        for (String hostId : hostIds) {
            candidateEndpoints.add(db.getEndpoint(hostId));
        }
        return candidateEndpoints;
    }

    // 必需同步
    private synchronized BTreePage setLeafPageMovePlan(PageKey pageKey, LeafPageMovePlan leafPageMovePlan) {
        BTreePage page = root.binarySearchLeafPage(pageKey.key);
        if (page != null) {
            page.setLeafPageMovePlan(leafPageMovePlan);
        }
        return page;
    }

    private void moveLeafPageLazy(PageKey pageKey) {
        AOStorageService.addPendingTask(() -> {
            moveLeafPage(pageKey);
            return null;
        });
    }

    private void moveLeafPage(PageKey pageKey) {
        BTreePage p = root;
        BTreePage parent = p;
        int index = 0;
        while (p.isNode()) {
            index = p.binarySearch(pageKey.key);
            if (index < 0) {
                index = -index - 1;
                if (p.isRemoteChildPage(index))
                    return;
                parent = p;
                p = p.getChildPage(index);
            } else {
                index++;
                if (parent.isRemoteChildPage(index))
                    return;
                // 左边已经移动过了，那么右边就不需要再移
                if (parent.isRemoteChildPage(index - 1))
                    return;

                p = parent.getChildPage(index);
                String[] oldEndpoints;
                if (p.getReplicationHostIds() == null) {
                    oldEndpoints = new String[0];
                } else {
                    oldEndpoints = new String[p.getReplicationHostIds().size()];
                    p.getReplicationHostIds().toArray(oldEndpoints);
                }
                replicateOrMovePage(pageKey, p, parent, index, oldEndpoints, false);
                break;
            }
        }
    }

    // 处理三种场景:
    // 1. 从client_server模式转到sharding模式
    // 2. 从replication模式转到sharding模式
    // 3. 在sharding模式下发生page split时需要移动右边的page
    //
    // 前两种场景在移动page时所选定的目标节点可以是原来的节点，后一种不可以。
    // 除此之外，这三者并没有多大差异，只是oldEndpoints中包含的节点个数多少的问题，
    // client_server模式只有一个节点，在replication模式下，如果副本个数是1，那么也相当于client_server模式。
    private void replicateOrMovePage(PageKey pageKey, BTreePage p, BTreePage parent, int index, String[] oldEndpoints,
            boolean replicate) {
        Set<NetEndpoint> candidateEndpoints = getCandidateEndpoints();
        replicateOrMovePage(pageKey, p, parent, index, oldEndpoints, replicate, candidateEndpoints);
    }

    void replicateOrMovePage(PageKey pageKey, BTreePage p, BTreePage parent, int index, String[] oldEndpoints,
            boolean replicate, Set<NetEndpoint> candidateEndpoints) {
        if (oldEndpoints == null || oldEndpoints.length == 0) {
            DbException.throwInternalError("oldEndpoints is null");
        }

        List<NetEndpoint> oldReplicationEndpoints = getReplicationEndpoints(db, oldEndpoints);
        Set<NetEndpoint> oldEndpointSet;
        if (replicate) {
            // 允许选择原来的节点，所以用new HashSet<>(0)替代new HashSet<>(oldReplicationEndpoints)
            oldEndpointSet = new HashSet<>(0);
        } else {
            oldEndpointSet = new HashSet<>(oldReplicationEndpoints);
        }

        List<NetEndpoint> newReplicationEndpoints = db.getReplicationEndpoints(oldEndpointSet, candidateEndpoints);

        Session session = db.createInternalSession();
        LeafPageMovePlan leafPageMovePlan = null;

        if (oldEndpoints.length == 1) {
            leafPageMovePlan = new LeafPageMovePlan(oldEndpoints[0], newReplicationEndpoints, pageKey);
            p.setLeafPageMovePlan(leafPageMovePlan);
        } else {
            ReplicationSession rs = db.createReplicationSession(session, oldReplicationEndpoints);
            try (StorageCommand c = rs.createStorageCommand()) {
                LeafPageMovePlan plan = new LeafPageMovePlan(getLocalHostId(), newReplicationEndpoints, pageKey);
                leafPageMovePlan = c.prepareMoveLeafPage(getName(), plan);
            }

            if (leafPageMovePlan == null)
                return;

            // 重新按key找到page，因为经过前面的操作后，
            // 可能page已经有新数据了，如果只移动老的，会丢失数据
            p = setLeafPageMovePlan(pageKey, leafPageMovePlan);

            if (!leafPageMovePlan.moverHostId.equals(getLocalHostId())) {
                p.setReplicationHostIds(leafPageMovePlan.getReplicationEndpoints());
                return;
            }
        }

        p.setReplicationHostIds(toHostIds(db, newReplicationEndpoints));
        NetEndpoint localEndpoint = getLocalEndpoint();

        Set<NetEndpoint> otherEndpoints = new HashSet<>(candidateEndpoints);
        otherEndpoints.removeAll(newReplicationEndpoints);

        if (parent != null && !replicate && !newReplicationEndpoints.contains(localEndpoint)) {
            PageReference r = PageReference.createRemotePageReference(pageKey.key, index == 0);
            r.replicationHostIds = p.getReplicationHostIds();
            parent.setChild(index, r);
        }
        if (!replicate) {
            otherEndpoints.removeAll(oldReplicationEndpoints);
            newReplicationEndpoints.removeAll(oldReplicationEndpoints);
        }

        if (newReplicationEndpoints.contains(localEndpoint)) {
            newReplicationEndpoints.remove(localEndpoint);
        }

        // 移动page到新的复制节点(page中包含数据)
        if (!newReplicationEndpoints.isEmpty()) {
            ReplicationSession rs = db.createReplicationSession(session, newReplicationEndpoints, true);
            moveLeafPage(leafPageMovePlan.pageKey, p, rs, false, !replicate);
        }

        // 当前节点已经不是副本所在节点
        if (parent != null && replicate && otherEndpoints.contains(localEndpoint)) {
            otherEndpoints.remove(localEndpoint);
            PageReference r = PageReference.createRemotePageReference(pageKey.key, index == 0);
            r.replicationHostIds = p.getReplicationHostIds();
            parent.setChild(index, r);
        }

        // 移动page到其他节点(page中不包含数据，只包含这个page各数据副本所在节点信息)
        if (!otherEndpoints.isEmpty()) {
            ReplicationSession rs = db.createReplicationSession(session, otherEndpoints, true);
            moveLeafPage(leafPageMovePlan.pageKey, p, rs, true, !replicate);
        }
    }

    private void moveLeafPage(PageKey pageKey, BTreePage page, ReplicationSession rs, boolean remote, boolean addPage) {
        try (DataBuffer buff = DataBuffer.create(); StorageCommand c = rs.createStorageCommand()) {
            page.writeLeaf(buff, remote);
            ByteBuffer pageBuffer = buff.getAndFlipBuffer();
            c.moveLeafPage(getName(), pageKey, pageBuffer, addPage);
        }
    }

    /**
     * Use the new root page from now on.
     * 
     * @param newRoot the new root page
     */
    protected void newRoot(BTreePage newRoot) {
        if (root != newRoot) {
            root = newRoot;
        }
    }

    @Override
    public synchronized V putIfAbsent(K key, V value) {
        V old = get(key);
        if (old == null) {
            put(key, value);
        }
        return old;
    }

    @Override
    @SuppressWarnings("unchecked")
    public V remove(K key) {
        beforeWrite();
        V result = get(key);
        if (result == null) {
            return null;
        }
        synchronized (this) {
            BTreePage p = root.copy();
            result = (V) remove(p, key);
            if (p.isNode() && p.isEmpty()) {
                p.removePage();
                p = BTreeLeafPage.createEmpty(this);
            }
            newRoot(p);
        }
        return result;
    }

    /**
     * Remove a key-value pair.
     * 
     * @param p the page (may not be null)
     * @param key the key
     * @return the old value, or null if the key did not exist
     */
    protected Object remove(BTreePage p, Object key) {
        int index = p.binarySearch(key);
        Object result = null;
        if (p.isLeaf()) {
            if (index >= 0) {
                result = p.getValue(index);
                p.remove(index);
            }
            return result;
        }
        // node
        if (index < 0) {
            index = -index - 1;
        } else {
            index++;
        }
        BTreePage cOld = p.getChildPage(index);
        BTreePage c = cOld.copy();
        result = remove(c, key);
        if (result == null || c.isNotEmpty()) {
            // no change, or there are more nodes
            p.setChild(index, c);
        } else {
            PageKey pageKey = p.getChildPageReference(index).pageKey;
            // this child was deleted
            if (p.getKeyCount() == 0) { // 如果p的子节点只剩一个叶子节点时，keyCount为0
                p.setChild(index, c);
                c.removePage(); // 直接删除最后一个子节点，父节点在remove(Object)那里删除
            } else {
                p.remove(index); // 删除没有记录的子节点
            }
            if (isShardingMode && c.isLeaf())
                removeLeafPage(pageKey, c);
        }
        return result;
    }

    private void removeLeafPage(PageKey pageKey, BTreePage leafPage) {
        if (leafPage.getReplicationHostIds().get(0).equals(getLocalHostId())) {
            AOStorageService.submitTask(() -> {
                List<NetEndpoint> oldReplicationEndpoints = getReplicationEndpoints(leafPage);
                Set<NetEndpoint> otherEndpoints = getCandidateEndpoints();
                otherEndpoints.removeAll(oldReplicationEndpoints);
                Session session = db.createInternalSession();
                ReplicationSession rs = db.createReplicationSession(session, otherEndpoints, true);
                try (StorageCommand c = rs.createStorageCommand()) {
                    c.removeLeafPage(getName(), pageKey);
                }
            });
        }
    }

    @Override
    public synchronized boolean replace(K key, V oldValue, V newValue) {
        V old = get(key);
        if (areValuesEqual(old, oldValue)) {
            put(key, newValue);
            return true;
        }
        return false;
    }

    @Override
    public K firstKey() {
        return getFirstLast(true);
    }

    @Override
    public K lastKey() {
        return getFirstLast(false);
    }

    /**
     * Get the first (lowest) or last (largest) key.
     * 
     * @param first whether to retrieve the first key
     * @return the key, or null if the map is empty
     */
    @SuppressWarnings("unchecked")
    protected K getFirstLast(boolean first) {
        if (sizeAsLong() == 0) {
            return null;
        }
        BTreePage p = root;
        while (true) {
            if (p.isLeaf()) {
                return (K) p.getKey(first ? 0 : p.getKeyCount() - 1);
            }
            p = p.getChildPage(first ? 0 : getChildPageCount(p) - 1);
        }
    }

    @Override
    public K lowerKey(K key) {
        return getMinMax(key, true, true);
    }

    @Override
    public K floorKey(K key) {
        return getMinMax(key, true, false);
    }

    @Override
    public K higherKey(K key) {
        return getMinMax(key, false, true);
    }

    @Override
    public K ceilingKey(K key) {
        return getMinMax(key, false, false);
    }

    /**
     * Get the smallest or largest key using the given bounds.
     * 
     * @param key the key
     * @param min whether to retrieve the smallest key
     * @param excluding if the given upper/lower bound is exclusive
     * @return the key, or null if no such key exists
     */
    protected K getMinMax(K key, boolean min, boolean excluding) {
        return getMinMax(root, key, min, excluding);
    }

    @SuppressWarnings("unchecked")
    private K getMinMax(BTreePage p, K key, boolean min, boolean excluding) {
        if (p.isLeaf()) {
            int x = p.binarySearch(key);
            if (x < 0) {
                x = -x - (min ? 2 : 1);
            } else if (excluding) {
                x += min ? -1 : 1;
            }
            if (x < 0 || x >= p.getKeyCount()) {
                return null;
            }
            return (K) p.getKey(x);
        }
        int x = p.binarySearch(key);
        if (x < 0) {
            x = -x - 1;
        } else {
            x++;
        }
        while (true) {
            if (x < 0 || x >= getChildPageCount(p)) {
                return null;
            }
            K k = getMinMax(p.getChildPage(x), key, min, excluding);
            if (k != null) {
                return k;
            }
            x += min ? -1 : 1;
        }
    }

    @Override
    public boolean areValuesEqual(Object a, Object b) {
        if (a == b) {
            return true;
        } else if (a == null || b == null) {
            return false;
        }
        return valueType.compare(a, b) == 0;
    }

    @Override
    public int size() {
        long size = sizeAsLong();
        return size > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) size;
    }

    @Override
    public long sizeAsLong() {
        return root.getTotalCount();
    }

    @Override
    public boolean containsKey(K key) {
        return get(key) != null;
    }

    @Override
    public boolean isEmpty() {
        return sizeAsLong() == 0;
    }

    @Override
    public boolean isInMemory() {
        return false;
    }

    @Override
    public StorageMapCursor<K, V> cursor(K from) {
        return cursor(IterationParameters.create(from));
    }

    @Override
    public StorageMapCursor<K, V> cursor(IterationParameters<K> parameters) {
        if (parameters.pageKeys == null)
            return new BTreeCursor<>(this, root, parameters);
        else
            return new PageKeyCursor<>(root, parameters);
    }

    @Override
    public synchronized void clear() {
        beforeWrite();
        root.removeAllRecursive();
        newRoot(BTreeLeafPage.createEmpty(this));
    }

    @Override
    public void remove() {
        btreeStorage.remove();
        closeMap();
    }

    @Override
    public boolean isClosed() {
        return btreeStorage.isClosed();
    }

    @Override
    public void close() {
        closeMap();
        btreeStorage.close();
    }

    private void closeMap() {
        storage.closeMap(name);
    }

    @Override
    public void save() {
        btreeStorage.save();
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public BTreeStorage getBTreeStorage() {
        return btreeStorage;
    }

    /**
     * Get the child page count for this page. This is to allow another map
     * implementation to override the default, in case the last child is not to be used.
     * 
     * @param p the page
     * @return the number of direct children
     */
    protected int getChildPageCount(BTreePage p) {
        return p.getRawChildPageCount();
    }

    protected String getType() {
        return "BTree";
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public boolean equals(Object o) {
        return this == o;
    }

    @Override
    public String toString() {
        StringBuilder buff = new StringBuilder();
        DataUtils.appendMap(buff, "name", name);
        String type = getType();
        if (type != null) {
            DataUtils.appendMap(buff, "type", type);
        }
        return buff.toString();
    }

    public void printPage() {
        printPage(true);
    }

    public void printPage(boolean readOffLinePage) {
        System.out.println(root.getPrettyPageInfo(readOffLinePage));
    }

    @Deprecated
    public void transferToOld(WritableByteChannel target, K firstKey, K lastKey) throws IOException {
        if (firstKey == null)
            firstKey = firstKey();
        else
            firstKey = ceilingKey(firstKey);

        if (firstKey == null)
            return;

        if (lastKey == null)
            lastKey = lastKey();
        else
            lastKey = floorKey(lastKey);

        if (keyType.compare(firstKey, lastKey) > 0)
            return;

        BTreePage p = root;
        if (p.getTotalCount() > 0) {
            p.transferTo(target, firstKey, lastKey);
        }
    }

    @Override
    public void transferTo(WritableByteChannel target, K firstKey, K lastKey) throws IOException {
        ArrayList<PageKey> pageKeys = new ArrayList<>();
        getEndpointToPageKeyMap(null, firstKey, lastKey, pageKeys);

        int size = pageKeys.size();
        for (int i = 0; i < size; i++) {
            PageKey pk = pageKeys.get(i);
            long pos = pk.pos;
            try (DataBuffer buff = DataBuffer.create()) {
                keyType.write(buff, pk.key);
                buff.put((byte) (pk.first ? 1 : 0));
                ByteBuffer pageBuff = null;
                if (pos > 0) {
                    pageBuff = readPageBuff(pos);
                    int start = pageBuff.position();
                    int pageLength = pageBuff.getInt();
                    pageBuff.position(start);
                    pageBuff.limit(start + pageLength);
                } else {
                    BTreePage p = root.binarySearchLeafPage(pk.key);
                    p.replicatePage(buff, null);
                }

                ByteBuffer dataBuff = buff.getAndFlipBuffer();
                target.write(dataBuff);
                if (pageBuff != null)
                    target.write(pageBuff);
            }
        }
    }

    private ByteBuffer readPageBuff(long pos) {
        BTreeChunk chunk = btreeStorage.getChunk(pos);
        long filePos = BTreeStorage.getFilePos(PageUtils.getPageOffset(pos));
        long maxPos = chunk.blockCount * BTreeStorage.BLOCK_SIZE;
        int maxLength = PageUtils.getPageMaxLength(pos);
        return BTreePage.readPageBuff(chunk.fileStorage, maxLength, filePos, maxPos);
    }

    @Override
    public void transferFrom(ReadableByteChannel src) throws IOException {
    }

    // 1.root为空时怎么处理；2.不为空时怎么处理
    public void transferFrom(ReadableByteChannel src, long position, long count) throws IOException {
        ByteBuffer buff = ByteBuffer.allocateDirect((int) count);
        src.read(buff);
        buff.position((int) position);

        while (buff.remaining() > 0) {
            Object key = keyType.read(buff);
            boolean first = buff.get() == 1;
            PageKey pk = new PageKey(key, first);
            addLeafPage(pk, buff, true, true);
            // int pageLength = buff.getInt();
            // pos += pageLength;
            // buff.position(pos);
        }
    }

    @Deprecated
    public void transferFromOld(ReadableByteChannel src, long position, long count) throws IOException {
        BTreePage p = root;
        p.transferFrom(src, position, count);
    }

    @Override
    public synchronized void addLeafPage(PageKey pageKey, ByteBuffer page, boolean addPage) {
        addLeafPage(pageKey, page, addPage, false);
    }

    private BTreePage readStreamPage(ByteBuffer buff) {
        BTreePage p = new BTreeLeafPage(this);
        int chunkId = 0;
        int offset = buff.position();
        p.read(buff, chunkId, offset, buff.limit(), true);
        return p;
    }

    private BTreePage readLeafPage(ByteBuffer buff, boolean readStreamPage) {
        return readStreamPage ? readStreamPage(buff) : BTreePage.readLeafPage(this, buff);
    }

    private synchronized void addLeafPage(PageKey pageKey, ByteBuffer page, boolean addPage, boolean readStreamPage) {
        if (pageKey == null) {
            root = readLeafPage(page, readStreamPage);
            return;
        }
        BTreePage p = root;
        Object k = pageKey.key;
        if (p.isLeaf()) {
            Object[] keys = { k };
            BTreePage left = BTreeLeafPage.createEmpty(this);
            left.setReplicationHostIds(p.getReplicationHostIds());

            BTreePage right = readLeafPage(page, readStreamPage);

            PageReference[] children = { new PageReference(left, left.getPos(), left.getTotalCount(), k, true),
                    new PageReference(right, right.getPos(), right.getTotalCount(), k, false) };
            p = BTreePage.create(this, keys, null, children, right.getTotalCount(), 0);
            newRoot(p);
        } else {
            BTreePage parent = p;
            int index = 0;
            while (p.isNode()) {
                parent = p;
                index = p.binarySearch(k);
                if (index < 0) {
                    index = -index - 1;
                } else {
                    index++;
                }
                PageReference r = p.getChildPageReference(index);
                if (r.isRemotePage()) {
                    break;
                }
                p = p.getChildPage(index);
            }
            BTreePage right = readLeafPage(page, readStreamPage);
            if (addPage) {
                BTreePage left = parent.getChildPage(index);
                parent.setChild(index, right);
                parent.insertNode(index, k, left);
            } else {
                parent.setChild(index, right);
            }
        }
    }

    @Override
    public synchronized void removeLeafPage(PageKey pageKey) {
        beforeWrite();
        BTreePage p;
        if (pageKey == null) { // 说明删除的是root leaf page
            p = BTreeLeafPage.createEmpty(this);
        } else {
            p = root.copy();
            removeLeafPage(p, pageKey);
            if (p.isNode() && p.isEmpty()) {
                p.removePage();
                p = BTreeLeafPage.createEmpty(this);
            }
        }
        newRoot(p);
    }

    private void removeLeafPage(BTreePage p, PageKey pk) {
        if (p.isLeaf()) {
            return;
        }
        // node page
        int x = p.binarySearch(pk.key);
        if (x < 0) {
            x = -x - 1;
        } else {
            x++;
        }
        if (pk.first && p.isLeafChildPage(x)) {
            x = 0;
        }
        BTreePage cOld = p.getChildPage(x);
        BTreePage c = cOld.copy();
        removeLeafPage(c, pk);
        if (c.isLeaf())
            c.removePage();
        else
            p.setChild(x, c);
        if (p.getKeyCount() == 0) { // 如果p的子节点只剩一个叶子节点时，keyCount为0
            p.setChild(x, (BTreePage) null);
        } else {
            if (c.isLeaf())
                p.remove(x);
        }
    }

    @Override
    public List<NetEndpoint> getReplicationEndpoints(Object key) {
        return getReplicationEndpoints(root, key);
    }

    private List<NetEndpoint> getReplicationEndpoints(BTreePage p, Object key) {
        if (p.isLeaf()) {
            return getReplicationEndpoints(p);
        }
        int index = p.binarySearch(key);
        // p is a node
        if (index < 0) {
            index = -index - 1;
        } else {
            index++;
        }
        return getReplicationEndpoints(p.getChildPage(index), key);
    }

    private List<NetEndpoint> getReplicationEndpoints(BTreePage p) {
        return getReplicationEndpoints(db, p.getReplicationHostIds());
    }

    static List<NetEndpoint> getReplicationEndpoints(IDatabase db, String[] replicationHostIds) {
        return getReplicationEndpoints(db, Arrays.asList(replicationHostIds));
    }

    static List<NetEndpoint> getReplicationEndpoints(IDatabase db, List<String> replicationHostIds) {
        int size = replicationHostIds.size();
        List<NetEndpoint> replicationEndpoints = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            replicationEndpoints.add(db.getEndpoint(replicationHostIds.get(i)));
        }
        return replicationEndpoints;
    }

    private List<NetEndpoint> getLastPageReplicationEndpoints() {
        BTreePage p = root;
        while (true) {
            if (p.isLeaf()) {
                return getReplicationEndpoints(p);
            }
            p = p.getChildPage(getChildPageCount(p) - 1);
        }
    }

    NetEndpoint getLocalEndpoint() {
        return NetEndpoint.getLocalP2pEndpoint();
    }

    @Override
    public Object replicationPut(Session session, Object key, Object value, StorageDataType valueType) {
        List<NetEndpoint> replicationEndpoints = getReplicationEndpoints(key);
        ReplicationSession rs = db.createReplicationSession(session, replicationEndpoints);
        try (DataBuffer k = DataBuffer.create();
                DataBuffer v = DataBuffer.create();
                StorageCommand c = rs.createStorageCommand()) {
            ByteBuffer keyBuffer = k.write(keyType, key);
            ByteBuffer valueBuffer = v.write(valueType, value);
            byte[] oldValue = (byte[]) c.executePut(null, getName(), keyBuffer, valueBuffer, false);
            if (oldValue == null)
                return null;
            return valueType.read(ByteBuffer.wrap(oldValue));
        }
    }

    @Override
    public Object replicationGet(Session session, Object key) {
        List<NetEndpoint> replicationEndpoints = getReplicationEndpoints(key);
        ReplicationSession rs = db.createReplicationSession(session, replicationEndpoints);
        try (DataBuffer k = DataBuffer.create(); StorageCommand c = rs.createStorageCommand()) {
            ByteBuffer keyBuffer = k.write(keyType, key);
            byte[] value = (byte[]) c.executeGet(getName(), keyBuffer);
            if (value == null)
                return null;
            return valueType.read(ByteBuffer.wrap(value));
        }
    }

    @Override
    public Object replicationAppend(Session session, Object value, StorageDataType valueType) {
        List<NetEndpoint> replicationEndpoints = getLastPageReplicationEndpoints();
        ReplicationSession rs = db.createReplicationSession(session, replicationEndpoints);
        try (DataBuffer v = DataBuffer.create(); StorageCommand c = rs.createStorageCommand()) {
            ByteBuffer valueBuffer = v.write(valueType, value);
            return c.executeAppend(null, getName(), valueBuffer, null);
        }
    }

    @Override
    public synchronized LeafPageMovePlan prepareMoveLeafPage(LeafPageMovePlan leafPageMovePlan) {
        BTreePage p = root.binarySearchLeafPage(leafPageMovePlan.pageKey.key);
        if (p.isLeaf()) {
            // 老的index < 新的index时，说明上一次没有达成一致，进行第二次协商
            if (p.getLeafPageMovePlan() == null || p.getLeafPageMovePlan().getIndex() < leafPageMovePlan.getIndex()) {
                p.setLeafPageMovePlan(leafPageMovePlan);
            }
            return p.getLeafPageMovePlan();
        }
        return null;
    }

    public void replicateRootPage(DataBuffer p) {
        root.replicatePage(p, NetEndpoint.getLocalTcpEndpoint());
    }

    public void setOldEndpoints(String[] oldEndpoints) {
        this.oldEndpoints = oldEndpoints;
    }

    public void setDatabase(IDatabase db) {
        this.db = db;
    }

    public void setRunMode(RunMode runMode) {
        this.runMode = runMode;
    }

    boolean isShardingMode() {
        if (runMode != null) {
            return runMode == RunMode.SHARDING;
        }
        return db.isShardingMode();
    }

    @Override
    public ByteBuffer readPage(PageKey pageKey) {
        BTreePage p = root;
        Object k = pageKey.key;
        if (p.isLeaf()) {
            throw DbException.throwInternalError("readPage: pageKey=" + pageKey);
        }
        BTreePage parent = p;
        int index = 0;
        while (p.isNode()) {
            index = p.binarySearch(k);
            if (index < 0) {
                index = -index - 1;
                parent = p;
                p = p.getChildPage(index);
            } else {
                index++;
                return replicateOrMovePage(pageKey, parent.getChildPage(index), parent, index);
            }
        }
        return null;
    }

    private ByteBuffer replicatePage(BTreePage p) {
        try (DataBuffer buff = DataBuffer.create()) {
            p.replicatePage(buff, getLocalEndpoint());
            ByteBuffer pageBuffer = buff.getAndFlipBuffer();
            return pageBuffer.slice();
        }
    }

    private ByteBuffer replicateOrMovePage(PageKey pageKey, BTreePage p, BTreePage parent, int index) {
        // 从client_server模式到replication模式
        if (!isShardingMode()) {
            return replicatePage(p);
        }

        // 如果该page已经处理过，那么直接返回它
        if (p.getReplicationHostIds() != null) {
            return replicatePage(p);
        }

        // 以下处理从client_server或replication模式到sharding模式的场景
        // ---------------------------------------------------------------
        replicateOrMovePage(pageKey, p, parent, index, oldEndpoints, true);

        return replicatePage(p);
    }

    private static List<String> toHostIds(IDatabase db, List<NetEndpoint> endpoints) {
        List<String> hostIds = new ArrayList<>(endpoints.size());
        for (NetEndpoint e : endpoints) {
            String id = db.getHostId(e);
            hostIds.add(id);
        }
        return hostIds;
    }

    @Override
    public synchronized void setRootPage(ByteBuffer buff) {
        root = BTreePage.readReplicatedPage(this, buff);
        if (root.isNode() && !getName().endsWith("_0")) { // 只异步读非SYS表
            root.readRemotePages();
        }
    }

    public void replicateAllRemotePages() {
        root.readRemotePagesRecursive();
    }

    public void moveAllLocalLeafPages(String[] oldEndpoints, String[] newEndpoints) {
        root.moveAllLocalLeafPages(oldEndpoints, newEndpoints);
    }

    @Override
    public long getDiskSpaceUsed() {
        return btreeStorage.getDiskSpaceUsed();
    }

    @Override
    public long getMemorySpaceUsed() {
        return btreeStorage.getMemorySpaceUsed();
    }

    // 查找闭区间[from, to]对应的所有leaf page，并建立这些leaf page所在节点与page key的映射关系
    // 该方法不需要读取leaf page或remote page
    @Override
    public Map<String, List<PageKey>> getEndpointToPageKeyMap(Session session, K from, K to) {
        return getEndpointToPageKeyMap(session, from, to, null);
    }

    public Map<String, List<PageKey>> getEndpointToPageKeyMap(Session session, K from, K to, List<PageKey> pageKeys) {
        Map<String, List<PageKey>> map = new HashMap<>();
        Random random = new Random();
        if (root.isLeaf()) {
            Object key = root.getKeyCount() == 0 ? null : root.getKey(0);
            getPageKey(map, random, pageKeys, root, 0, key);
        } else {
            dfs(map, random, from, to, pageKeys);
        }
        return map;
    }

    // 深度优先搜索(不使用递归)
    private void dfs(Map<String, List<PageKey>> map, Random random, K from, K to, List<PageKey> pageKeys) {
        CursorPos pos = null;
        BTreePage p = root;
        while (p.isNode()) {
            // 注意: index是子page的数组索引，不是keys数组的索引
            int index = from == null ? -1 : p.binarySearch(from);
            if (index < 0) {
                index = -index - 1;
            } else {
                index++;
            }
            pos = new CursorPos(p, index + 1, pos);
            if (p.isNodeChildPage(index)) {
                p = p.getChildPage(index);
            } else {
                getPageKeys(map, random, from, to, pageKeys, p, index);

                // from此时为null，代表从右边兄弟节点keys数组的0号索引开始
                from = null;
                // 转到上一层，遍历右边的兄弟节点
                for (;;) {
                    pos = pos.parent;
                    if (pos == null) {
                        return;
                    }
                    if (pos.index < getChildPageCount(pos.page)) {
                        if (pos.page.isNodeChildPage(pos.index)) {
                            p = pos.page.getChildPage(pos.index++);
                            break; // 只是退出for循环
                        }
                    }
                }
            }
        }
    }

    private void getPageKeys(Map<String, List<PageKey>> map, Random random, K from, K to, List<PageKey> pageKeys,
            BTreePage p, int index) {
        int keyCount = p.getKeyCount();
        if (keyCount > 1) {
            boolean needsCompare = false;
            if (to != null) {
                Object lastKey = p.getLastKey();
                if (keyType.compare(lastKey, to) >= 0) {
                    needsCompare = true;
                }
            }
            // node page的直接子page不会出现同时包含node page和leaf page的情况
            for (int size = getChildPageCount(p); index < size; index++) {
                int keyIndex = index - 1;
                Object k = p.getKey(keyIndex < 0 ? 0 : keyIndex);
                if (needsCompare && keyType.compare(k, to) > 0) {
                    return;
                }
                getPageKey(map, random, pageKeys, p, index, k);
            }
        } else if (keyCount == 1) {
            Object k = p.getKey(0);
            if (from == null || keyType.compare(from, k) < 0) {
                getPageKey(map, random, pageKeys, p, 0, k);
            }
            if ((from != null && keyType.compare(from, k) >= 0) //
                    || to == null //
                    || keyType.compare(to, k) >= 0) {
                getPageKey(map, random, pageKeys, p, 1, k);
            }
        } else { // 当keyCount=0时也是合法的，比如node page只删到剩一个leaf page时
            if (getChildPageCount(p) != 1) {
                throw DbException.throwInternalError();
            }
            Object k = p.getChildPageReference(0).pageKey.key;
            getPageKey(map, random, pageKeys, p, 0, k);
        }
    }

    private void getPageKey(Map<String, List<PageKey>> map, Random random, List<PageKey> pageKeys, BTreePage p,
            int index, Object key) {
        long pos;
        List<String> hostIds;
        if (p.isNode()) {
            PageReference pr = p.getChildPageReference(index);
            pos = pr.pos;
            hostIds = pr.replicationHostIds;
        } else {
            pos = p.getPos();
            hostIds = p.getReplicationHostIds();
        }

        PageKey pk = new PageKey(key, index == 0, pos);
        if (pageKeys != null)
            pageKeys.add(pk);
        if (hostIds != null) {
            int i = random.nextInt(hostIds.size());
            String hostId = hostIds.get(i);
            List<PageKey> keys = map.get(hostId);
            if (keys == null) {
                keys = new ArrayList<>();
                map.put(hostId, keys);
            }
            keys.add(pk);
        }
    }

    // test only
    public BTreePage getRootPage() {
        return root;
    }

    public void setPageStorageMode(PageStorageMode pageStorageMode) {
        this.pageStorageMode = pageStorageMode;
    }
}
