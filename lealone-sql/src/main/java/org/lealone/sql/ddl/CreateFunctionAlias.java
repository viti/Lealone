/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.sql.ddl;

import org.lealone.common.exceptions.DbException;
import org.lealone.common.util.StringUtils;
import org.lealone.db.Database;
import org.lealone.db.DbObjectType;
import org.lealone.db.ServerSession;
import org.lealone.db.api.ErrorCode;
import org.lealone.db.schema.FunctionAlias;
import org.lealone.db.schema.Schema;
import org.lealone.sql.SQLStatement;

/**
 * This class represents the statement
 * CREATE ALIAS
 * 
 * @author H2 Group
 * @author zhh
 */
public class CreateFunctionAlias extends SchemaStatement {

    private String aliasName;
    private String javaClassMethod;
    private boolean deterministic;
    private boolean ifNotExists;
    private boolean force;
    private String source;
    private boolean bufferResultSetToLocalTemp = true;

    public CreateFunctionAlias(ServerSession session, Schema schema) {
        super(session, schema);
    }

    @Override
    public int getType() {
        return SQLStatement.CREATE_ALIAS;
    }

    @Override
    public int update() {
        session.getUser().checkAdmin();
        Database db = session.getDatabase();
        synchronized (getSchema().getLock(DbObjectType.FUNCTION_ALIAS)) {
            if (getSchema().findFunction(aliasName) != null) {
                if (!ifNotExists) {
                    throw DbException.get(ErrorCode.FUNCTION_ALIAS_ALREADY_EXISTS_1, aliasName);
                }
            } else {
                int id = getObjectId();
                FunctionAlias functionAlias;
                if (javaClassMethod != null) {
                    functionAlias = FunctionAlias.newInstance(getSchema(), id, aliasName, javaClassMethod, force,
                            bufferResultSetToLocalTemp);
                } else {
                    functionAlias = FunctionAlias.newInstanceFromSource(getSchema(), id, aliasName, source, force,
                            bufferResultSetToLocalTemp);
                }
                functionAlias.setDeterministic(deterministic);
                db.addSchemaObject(session, functionAlias);
            }
        }
        return 0;
    }

    public void setAliasName(String name) {
        this.aliasName = name;
    }

    /**
     * Set the qualified method name after removing whitespace.
     *
     * @param method the qualified method name
     */
    public void setJavaClassMethod(String method) {
        this.javaClassMethod = StringUtils.replaceAll(method, " ", "");
    }

    public void setIfNotExists(boolean ifNotExists) {
        this.ifNotExists = ifNotExists;
    }

    public void setForce(boolean force) {
        this.force = force;
    }

    public void setDeterministic(boolean deterministic) {
        this.deterministic = deterministic;
    }

    /**
     * Should the return value ResultSet be buffered in a local temporary file?
     *
     * @param b the new value
     */
    public void setBufferResultSetToLocalTemp(boolean b) {
        this.bufferResultSetToLocalTemp = b;
    }

    public void setSource(String source) {
        this.source = source;
    }

}
