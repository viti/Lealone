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
package org.lealone.db.index;

import org.lealone.common.util.StatementBuilder;
import org.lealone.db.ServerSession;
import org.lealone.db.result.Result;
import org.lealone.db.result.Row;
import org.lealone.db.result.SearchRow;
import org.lealone.db.result.SortOrder;
import org.lealone.db.table.Column;
import org.lealone.db.table.IndexColumn;
import org.lealone.db.table.StandardTable;
import org.lealone.db.value.Value;
import org.lealone.sql.PreparedStatement;

public class GlobalUniqueIndex extends IndexBase {

    public GlobalUniqueIndex(ServerSession session, StandardTable table, int id, String indexName,
            IndexColumn[] columns, IndexType indexType) {
        super(table, id, indexName, indexType, columns);
        if (!database.isStarting()) {
            checkIndexColumnTypes(columns);
        }

        StatementBuilder sql = new StatementBuilder("create ");
        if (table.isGlobalTemporary())
            sql.append("global temporary ");
        else if (table.isTemporary())
            sql.append("local temporary ");
        sql.append("table if not exists ").append(getName()).append("(_gui_row_id_ long,");

        for (Column c : getColumns()) {
            sql.append(c.getCreateSQL()).append(",");
        }

        sql.append("primary key(");
        for (Column c : getColumns()) {
            sql.appendExceptFirst(",");
            sql.append(c.getName());
        }
        sql.append("))");

        PreparedStatement prepared = session.prepareStatement(sql.toString(), true);
        prepared.setLocal(true);
        prepared.update();
    }

    @Override
    public void close(ServerSession session) {
        // ok
    }

    @Override
    public void add(ServerSession session, Row row) {
        StatementBuilder sql = new StatementBuilder("insert into ");
        sql.append(getName()).append("(_gui_row_id_");

        for (Column c : getColumns()) {
            sql.append(",");
            sql.append(c.getName());
        }

        sql.append(") values (");
        sql.append(row.getKey());

        for (Column c : getColumns()) {
            sql.append(",");
            Value v = row.getValue(c.getColumnId());
            if (v == null) {
                sql.append("DEFAULT");
            } else {
                sql.append(v.getSQL());
            }
        }
        sql.append(")");

        PreparedStatement prepared = session.prepareStatement(sql.toString(), true);
        prepared.setLocal(false);
        prepared.update();
    }

    @Override
    public void remove(ServerSession session, Row row) {
        StatementBuilder sql = new StatementBuilder("delete from ");
        sql.append(getName());
        if (row != null) {
            sql.append(" where ");

            for (Column c : getColumns()) {
                sql.appendExceptFirst(" and ");
                sql.append(c.getName()).append("=");
                Value v = row.getValue(c.getColumnId());
                if (v != null) {
                    sql.append(v.getSQL());
                }
            }
        }

        PreparedStatement prepared = session.prepareStatement(sql.toString(), true);
        prepared.setLocal(false);
        prepared.update();
    }

    @Override
    public Cursor find(ServerSession session, SearchRow first, SearchRow last) {
        return find(session, first, false, last);
    }

    private Cursor find(ServerSession session, SearchRow first, boolean bigger, SearchRow last) {
        StatementBuilder sql = new StatementBuilder("select _gui_row_id_");
        for (Column c : getColumns()) {
            sql.append(",");
            sql.append(c.getName());
        }
        sql.append(" from ").append(getName());

        if (first != null || last != null) {
            sql.append(" where ");

            for (Column c : getColumns()) {
                sql.appendExceptFirst(" and ");
                if (first != null) {
                    sql.append(c.getName()).append(">=");
                    Value v = first.getValue(c.getColumnId());
                    if (v != null) {
                        sql.append(v.getSQL());
                    }
                }
                if (last != null) {
                    sql.append(c.getName()).append("<=");
                    Value v = last.getValue(c.getColumnId());
                    if (v != null) {
                        sql.append(v.getSQL());
                    }
                }
            }
        }

        PreparedStatement prepared = session.prepareStatement(sql.toString(), true);
        prepared.setLocal(false);
        Result result = prepared.query(0);
        if (bigger)
            result.next();
        return new GlobalUniqueIndexTableCursor(result);
    }

    @Override
    public double getCost(ServerSession session, int[] masks, SortOrder sortOrder) {
        return Double.MAX_VALUE;
    }

    @Override
    public void remove(ServerSession session) {
        PreparedStatement prepared = session.prepareStatement("drop table if exists " + getName(), true);
        prepared.setLocal(true);
        prepared.update();
    }

    @Override
    public void truncate(ServerSession session) {
        PreparedStatement prepared = session.prepareStatement("truncate table " + getName(), true);
        prepared.setLocal(true);
        prepared.update();
    }

    @Override
    public boolean canGetFirstOrLast() {
        return false;
    }

    @Override
    public Cursor findFirstOrLast(ServerSession session, boolean first) {
        return find(session, null, false, null);
    }

    @Override
    public boolean needRebuild() {
        return false;
    }

    @Override
    public long getRowCount(ServerSession session) {
        StatementBuilder sql = new StatementBuilder("select count(*) from ");
        sql.append(getName());

        if (session == null)
            session = getDatabase().getSystemSession();
        PreparedStatement prepared = session.prepareStatement(sql.toString(), true);
        prepared.setLocal(false);
        Result result = prepared.query(0);
        return result.getRowCount();
    }

    @Override
    public long getRowCountApproximation() {
        return getRowCount(null);
    }

    @Override
    public void checkRename() {
        // ok
    }

    @Override
    public void rename(String newName) {
        StatementBuilder sql = new StatementBuilder("alter table ");
        sql.append(getName()).append(" rename to").append(newName);

        PreparedStatement prepared = getDatabase().getSystemSession().prepareStatement(sql.toString(), true);
        prepared.setLocal(true);
        prepared.update();
    }

    private class GlobalUniqueIndexTableCursor implements Cursor {
        final Result result;

        public GlobalUniqueIndexTableCursor(Result result) {
            this.result = result;
        }

        @Override
        public Row get() {
            Row row = new Row(result.currentRow(), 0);
            row.setKey(row.getValue(0).getLong());
            return row;
        }

        @Override
        public SearchRow getSearchRow() {
            return get();
        }

        @Override
        public boolean next() {
            return result.next();
        }

        @Override
        public boolean previous() {
            return false;
        }
    }
}
