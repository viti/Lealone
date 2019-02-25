/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.sql.expression;

import org.lealone.common.exceptions.DbException;
import org.lealone.common.util.StringUtils;
import org.lealone.db.ServerSession;
import org.lealone.db.api.ErrorCode;
import org.lealone.db.value.Value;
import org.lealone.sql.optimizer.ColumnResolver;
import org.lealone.sql.optimizer.TableFilter;

/**
 * A wildcard expression as in SELECT * FROM TEST.
 * This object is only used temporarily during the parsing phase, and later
 * replaced by column expressions.
 */
public class Wildcard extends Expression {
    private final String schema;
    private final String table;

    public Wildcard(String schema, String table) {
        this.schema = schema;
        this.table = table;
    }

    @Override
    public boolean isWildcard() {
        return true;
    }

    @Override
    public Value getValue(ServerSession session) {
        throw DbException.throwInternalError();
    }

    @Override
    public int getType() {
        throw DbException.throwInternalError();
    }

    @Override
    public void mapColumns(ColumnResolver resolver, int level) {
        throw DbException.get(ErrorCode.SYNTAX_ERROR_1, table);
    }

    @Override
    public Expression optimize(ServerSession session) {
        throw DbException.get(ErrorCode.SYNTAX_ERROR_1, table);
    }

    @Override
    public void setEvaluatable(TableFilter tableFilter, boolean b) {
        DbException.throwInternalError();
    }

    @Override
    public int getScale() {
        throw DbException.throwInternalError();
    }

    @Override
    public long getPrecision() {
        throw DbException.throwInternalError();
    }

    @Override
    public int getDisplaySize() {
        throw DbException.throwInternalError();
    }

    @Override
    public String getSchemaName() {
        return schema;
    }

    @Override
    public String getTableName() {
        return table;
    }

    @Override
    public String getSQL(boolean isDistributed) {
        if (table == null) {
            return "*";
        }
        return StringUtils.quoteIdentifier(table) + ".*";
    }

    @Override
    public void updateAggregate(ServerSession session) {
        DbException.throwInternalError();
    }

    @Override
    public boolean isEverything(ExpressionVisitor visitor) {
        throw DbException.throwInternalError();
    }

    @Override
    public int getCost() {
        throw DbException.throwInternalError();
    }

}
