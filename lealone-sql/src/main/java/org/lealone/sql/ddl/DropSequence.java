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
import org.lealone.db.schema.Sequence;
import org.lealone.sql.SQLStatement;

/**
 * This class represents the statement
 * DROP SEQUENCE
 * 
 * @author H2 Group
 * @author zhh
 */
public class DropSequence extends SchemaStatement {

    private String sequenceName;
    private boolean ifExists;

    public DropSequence(ServerSession session, Schema schema) {
        super(session, schema);
    }

    @Override
    public int getType() {
        return SQLStatement.DROP_SEQUENCE;
    }

    public void setIfExists(boolean b) {
        ifExists = b;
    }

    public void setSequenceName(String sequenceName) {
        this.sequenceName = sequenceName;
    }

    @Override
    public int update() {
        session.getUser().checkAdmin();
        Database db = session.getDatabase();
        synchronized (getSchema().getLock(DbObjectType.SEQUENCE)) {
            Sequence sequence = getSchema().findSequence(sequenceName);
            if (sequence == null) {
                if (!ifExists) {
                    throw DbException.get(ErrorCode.SEQUENCE_NOT_FOUND_1, sequenceName);
                }
            } else {
                if (sequence.getBelongsToTable()) {
                    throw DbException.get(ErrorCode.SEQUENCE_BELONGS_TO_A_TABLE_1, sequenceName);
                }
                db.removeSchemaObject(session, sequence);
            }
        }
        return 0;
    }

}
