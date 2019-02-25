/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.sql.ddl;

import org.lealone.common.exceptions.DbException;
import org.lealone.db.Database;
import org.lealone.db.DbObjectType;
import org.lealone.db.ServerSession;
import org.lealone.db.api.ErrorCode;
import org.lealone.db.schema.Schema;
import org.lealone.sql.SQLStatement;

/**
 * This class represents the statement
 * DROP SCHEMA
 * 
 * @author H2 Group
 * @author zhh
 */
public class DropSchema extends DefinitionStatement {

    private String schemaName;
    private boolean ifExists;

    public DropSchema(ServerSession session) {
        super(session);
    }

    @Override
    public int getType() {
        return SQLStatement.DROP_SCHEMA;
    }

    public void setSchemaName(String name) {
        this.schemaName = name;
    }

    public void setIfExists(boolean ifExists) {
        this.ifExists = ifExists;
    }

    @Override
    public int update() {
        session.getUser().checkAdmin();
        Database db = session.getDatabase();
        synchronized (db.getLock(DbObjectType.SCHEMA)) {
            Schema schema = db.findSchema(schemaName);
            if (schema == null) {
                if (!ifExists) {
                    throw DbException.get(ErrorCode.SCHEMA_NOT_FOUND_1, schemaName);
                }
            } else {
                if (!schema.canDrop()) {
                    throw DbException.get(ErrorCode.SCHEMA_CAN_NOT_BE_DROPPED_1, schemaName);
                }
                db.removeDatabaseObject(session, schema);
            }
        }
        return 0;
    }

}
