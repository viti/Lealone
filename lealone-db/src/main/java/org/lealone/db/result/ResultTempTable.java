/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.db.result;

import java.util.ArrayList;
import java.util.Arrays;

import org.lealone.db.Constants;
import org.lealone.db.Database;
import org.lealone.db.ServerSession;
import org.lealone.db.index.Cursor;
import org.lealone.db.index.Index;
import org.lealone.db.index.IndexType;
import org.lealone.db.schema.Schema;
import org.lealone.db.table.Column;
import org.lealone.db.table.CreateTableData;
import org.lealone.db.table.IndexColumn;
import org.lealone.db.table.Table;
import org.lealone.db.value.Value;
import org.lealone.db.value.ValueNull;
import org.lealone.sql.IExpression;

/**
 * This class implements the temp table buffer for the LocalResult class.
 */
public class ResultTempTable implements ResultExternal {

    private static final String COLUMN_NAME = "DATA";
    private final boolean distinct;
    private final SortOrder sort;
    private Index index;
    private final ServerSession session;
    private Table table;
    private Cursor resultCursor;
    private int rowCount;
    private final int columnCount;

    private final ResultTempTable parent;
    private boolean closed;
    private int childCount;
    private boolean containsLob;

    ResultTempTable(ServerSession session, IExpression[] expressions, boolean distinct, SortOrder sort) {
        this.session = session;
        this.distinct = distinct;
        this.sort = sort;
        this.columnCount = expressions.length;
        Schema schema = session.getDatabase().getSchema(Constants.SCHEMA_MAIN);
        CreateTableData data = new CreateTableData();
        for (int i = 0; i < expressions.length; i++) {
            int type = expressions[i].getType();
            Column col = new Column(COLUMN_NAME + i, type);
            if (type == Value.CLOB || type == Value.BLOB) {
                containsLob = true;
            }
            data.columns.add(col);
        }
        data.id = session.getDatabase().allocateObjectId();
        data.tableName = "TEMP_RESULT_SET_" + data.id;
        data.temporary = true;
        data.persistIndexes = false;
        data.persistData = true;
        data.create = true;
        data.session = session;
        table = schema.createTable(data);
        if (sort != null || distinct) {
            createIndex();
        }
        parent = null;
    }

    private ResultTempTable(ResultTempTable parent) {
        this.parent = parent;
        this.columnCount = parent.columnCount;
        this.distinct = parent.distinct;
        this.session = parent.session;
        this.table = parent.table;
        this.index = parent.index;
        this.rowCount = parent.rowCount;
        this.sort = parent.sort;
        this.containsLob = parent.containsLob;
        reset();
    }

    private void createIndex() {
        IndexColumn[] indexCols = null;
        if (sort != null) {
            int[] colIndex = sort.getQueryColumnIndexes();
            indexCols = new IndexColumn[colIndex.length];
            for (int i = 0; i < colIndex.length; i++) {
                IndexColumn indexColumn = new IndexColumn();
                indexColumn.column = table.getColumn(colIndex[i]);
                indexColumn.sortType = sort.getSortTypes()[i];
                indexColumn.columnName = COLUMN_NAME + i;
                indexCols[i] = indexColumn;
            }
        } else {
            indexCols = new IndexColumn[columnCount];
            for (int i = 0; i < columnCount; i++) {
                IndexColumn indexColumn = new IndexColumn();
                indexColumn.column = table.getColumn(i);
                indexColumn.columnName = COLUMN_NAME + i;
                indexCols[i] = indexColumn;
            }
        }
        String indexName = table.getSchema().getUniqueIndexName(session, table, Constants.PREFIX_INDEX);
        int indexId = session.getDatabase().allocateObjectId();
        IndexType indexType = IndexType.createNonUnique();
        index = table.addIndex(session, indexName, indexId, indexCols, indexType, true, null);
    }

    @Override
    public synchronized ResultExternal createShallowCopy() {
        if (parent != null) {
            return parent.createShallowCopy();
        }
        if (closed) {
            return null;
        }
        childCount++;
        return new ResultTempTable(this);
    }

    @Override
    public int removeRow(Value[] values) {
        Row row = convertToRow(values);
        Cursor cursor = find(row);
        if (cursor != null) {
            row = cursor.get();
            table.removeRow(session, row);
            rowCount--;
        }
        return rowCount;
    }

    @Override
    public boolean contains(Value[] values) {
        return find(convertToRow(values)) != null;
    }

    @Override
    public int addRow(Value[] values) {
        Row row = convertToRow(values);
        if (distinct) {
            Cursor cursor = find(row);
            if (cursor == null) {
                table.addRow(session, row);
                rowCount++;
            }
        } else {
            table.addRow(session, row);
            rowCount++;
        }
        return rowCount;
    }

    @Override
    public int addRows(ArrayList<Value[]> rows) {
        // speeds up inserting, but not really needed:
        if (sort != null) {
            sort.sort(rows);
        }
        for (Value[] values : rows) {
            addRow(values);
        }
        return rowCount;
    }

    private synchronized void closeChild() {
        if (--childCount == 0 && closed) {
            dropTable();
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (parent != null) {
            parent.closeChild();
        } else {
            if (childCount == 0) {
                dropTable();
            }
        }
    }

    private void dropTable() {
        if (table == null) {
            return;
        }
        if (containsLob) {
            // contains BLOB or CLOB: can not truncate now,
            // otherwise the BLOB and CLOB entries are removed
            return;
        }
        try {
            Database database = session.getDatabase();
            // Need to lock because not all of the code-paths
            // that reach here have already taken this lock,
            // notably via the close() paths.
            synchronized (session) {
                synchronized (database) {
                    table.truncate(session);
                }
            }
            // This session may not lock the sys table (except if it already has
            // locked it) because it must be committed immediately, otherwise
            // other threads can not access the sys table. If the table is not
            // removed now, it will be when the database is opened the next
            // time. (the table is truncated, so this is just one record)
            if (!database.isSysTableLocked()) {
                ServerSession sysSession = database.getSystemSession();
                table.removeChildrenAndResources(sysSession);
                if (index != null) {
                    // need to explicitly do this,
                    // as it's not registered in the system session
                    session.removeLocalTempTableIndex(index);
                }
                // the transaction must be committed immediately
                // TODO this synchronization cascade is very ugly
                synchronized (session) {
                    synchronized (sysSession) {
                        synchronized (database) {
                            sysSession.commit();
                        }
                    }
                }
            }
        } finally {
            table = null;
        }
    }

    @Override
    public void done() {
        // nothing to do
    }

    @Override
    public Value[] next() {
        if (resultCursor == null) {
            Index idx;
            if (distinct || sort != null) {
                idx = index;
            } else {
                idx = table.getScanIndex(session);
            }
            // sometimes the transaction is already committed,
            // in which case we can't use the session
            if (idx.getRowCount(session) == 0 && rowCount > 0) {
                // this means querying is not transactional
                resultCursor = idx.find((ServerSession) null, null, null);
            } else {
                // the transaction is still open
                resultCursor = idx.find(session, null, null);
            }
        }
        if (!resultCursor.next()) {
            return null;
        }
        Row row = resultCursor.get();
        return row.getValueList();
    }

    @Override
    public void reset() {
        resultCursor = null;
    }

    private Row convertToRow(Value[] values) {
        if (values.length < columnCount) {
            Value[] v2 = Arrays.copyOf(values, columnCount);
            for (int i = values.length; i < columnCount; i++) {
                v2[i] = ValueNull.INSTANCE;
            }
            values = v2;
        }
        return new Row(values, Row.MEMORY_CALCULATE);
    }

    private Cursor find(Row row) {
        if (index == null) {
            // for the case "in(select ...)", the query might
            // use an optimization and not create the index
            // up front
            createIndex();
        }
        Cursor cursor = index.find(session, row, row);
        while (cursor.next()) {
            SearchRow found = cursor.getSearchRow();
            boolean ok = true;
            Database db = session.getDatabase();
            for (int i = 0; i < row.getColumnCount(); i++) {
                if (!db.areEqual(row.getValue(i), found.getValue(i))) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return cursor;
            }
        }
        return null;
    }

}
