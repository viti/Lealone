/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.db;

import java.sql.Connection;
import java.sql.SQLException;

import org.lealone.common.exceptions.DbException;
import org.lealone.common.trace.Trace;
import org.lealone.common.util.Utils;
import org.lealone.db.api.Aggregate;
import org.lealone.db.api.AggregateFunction;
import org.lealone.db.value.DataType;

/**
 * Represents a user-defined aggregate function.
 */
public class UserAggregate extends DbObjectBase {

    private String className;
    private Class<?> javaClass;

    public UserAggregate(Database db, int id, String name, String className, boolean force) {
        super(db, id, name, Trace.FUNCTION);
        this.className = className;
        if (!force) {
            getInstance();
        }
    }

    @Override
    public DbObjectType getType() {
        return DbObjectType.AGGREGATE;
    }

    public Aggregate getInstance() {
        if (javaClass == null) {
            javaClass = Utils.loadUserClass(className);
        }
        Object obj;
        try {
            obj = javaClass.newInstance();
            Aggregate agg;
            if (obj instanceof Aggregate) {
                agg = (Aggregate) obj;
            } else {
                agg = new AggregateWrapper((AggregateFunction) obj);
            }
            return agg;
        } catch (Exception e) {
            throw DbException.convert(e);
        }
    }

    @Override
    public String getCreateSQL() {
        return "CREATE FORCE AGGREGATE " + getSQL() + " FOR " + database.quoteIdentifier(className);
    }

    @Override
    public String getDropSQL() {
        return "DROP AGGREGATE IF EXISTS " + getSQL();
    }

    @Override
    public synchronized void removeChildrenAndResources(ServerSession session) {
        super.removeChildrenAndResources(session);
        className = null;
        javaClass = null;
    }

    @Override
    public void checkRename() {
        throw DbException.getUnsupportedException("AGGREGATE");
    }

    public String getJavaClassName() {
        return className;
    }

    /**
     * Wrap {@link AggregateFunction} in order to behave as
     * {@link org.lealone.db.api.Aggregate}
     **/
    private static class AggregateWrapper implements Aggregate {
        private final AggregateFunction aggregateFunction;

        AggregateWrapper(AggregateFunction aggregateFunction) {
            this.aggregateFunction = aggregateFunction;
        }

        @Override
        public void init(Connection conn) throws SQLException {
            aggregateFunction.init(conn);
        }

        @Override
        public int getInternalType(int[] inputTypes) throws SQLException {
            int[] sqlTypes = new int[inputTypes.length];
            for (int i = 0; i < inputTypes.length; i++) {
                sqlTypes[i] = DataType.convertTypeToSQLType(inputTypes[i]);
            }
            return DataType.convertSQLTypeToValueType(aggregateFunction.getType(sqlTypes));
        }

        @Override
        public void add(Object value) throws SQLException {
            aggregateFunction.add(value);
        }

        @Override
        public Object getResult() throws SQLException {
            return aggregateFunction.getResult();
        }
    }

}
