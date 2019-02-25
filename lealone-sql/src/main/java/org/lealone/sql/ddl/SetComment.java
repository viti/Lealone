/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.sql.ddl;

import org.lealone.common.exceptions.DbException;
import org.lealone.db.Comment;
import org.lealone.db.Database;
import org.lealone.db.DbObject;
import org.lealone.db.DbObjectType;
import org.lealone.db.ServerSession;
import org.lealone.db.api.ErrorCode;
import org.lealone.db.table.Table;
import org.lealone.sql.SQLStatement;
import org.lealone.sql.expression.Expression;

/**
 * This class represents the statement
 * COMMENT
 * 
 * @author H2 Group
 * @author zhh
 */
public class SetComment extends DefinitionStatement {

    private String schemaName;
    private String objectName;
    private boolean column;
    private String columnName;
    private DbObjectType objectType;
    private Expression expr;

    public SetComment(ServerSession session) {
        super(session);
    }

    @Override
    public int getType() {
        return SQLStatement.COMMENT;
    }

    @Override
    public int update() {
        Database db = session.getDatabase();
        session.getUser().checkAdmin();
        DbObject object = null;
        int errorCode = ErrorCode.GENERAL_ERROR_1;
        if (schemaName == null) {
            schemaName = session.getCurrentSchemaName();
        }
        switch (objectType) {
        case CONSTANT:
            object = db.getSchema(schemaName).getConstant(objectName);
            break;
        case CONSTRAINT:
            object = db.getSchema(schemaName).getConstraint(objectName);
            break;
        case FUNCTION_ALIAS:
            object = db.getSchema(schemaName).findFunction(objectName);
            errorCode = ErrorCode.FUNCTION_ALIAS_NOT_FOUND_1;
            break;
        case INDEX:
            object = db.getSchema(schemaName).getIndex(objectName);
            break;
        case ROLE:
            schemaName = null;
            object = db.findRole(objectName);
            errorCode = ErrorCode.ROLE_NOT_FOUND_1;
            break;
        case SCHEMA:
            schemaName = null;
            object = db.findSchema(objectName);
            errorCode = ErrorCode.SCHEMA_NOT_FOUND_1;
            break;
        case SEQUENCE:
            object = db.getSchema(schemaName).getSequence(objectName);
            break;
        case TABLE_OR_VIEW:
            object = db.getSchema(schemaName).getTableOrView(session, objectName);
            break;
        case TRIGGER:
            object = db.getSchema(schemaName).findTrigger(objectName);
            errorCode = ErrorCode.TRIGGER_NOT_FOUND_1;
            break;
        case USER:
            schemaName = null;
            object = db.getUser(objectName);
            break;
        case USER_DATATYPE:
            schemaName = null;
            object = db.findUserDataType(objectName);
            errorCode = ErrorCode.USER_DATA_TYPE_ALREADY_EXISTS_1;
            break;
        default:
        }
        if (object == null) {
            throw DbException.get(errorCode, objectName);
        }
        String text = expr.optimize(session).getValue(session).getString();
        if (column) {
            Table table = (Table) object;
            table.getColumn(columnName).setComment(text);
        } else {
            object.setComment(text);
        }
        if (column || objectType == DbObjectType.TABLE_OR_VIEW || objectType == DbObjectType.USER
                || objectType == DbObjectType.INDEX || objectType == DbObjectType.CONSTRAINT) {
            db.updateMeta(session, object);
        } else {
            Comment comment = db.findComment(object);
            if (comment == null) {
                if (text == null) {
                    // reset a non-existing comment - nothing to do
                } else {
                    int id = getObjectId();
                    comment = new Comment(db, id, object);
                    comment.setCommentText(text);
                    db.addDatabaseObject(session, comment);
                }
            } else {
                if (text == null) {
                    db.removeDatabaseObject(session, comment);
                } else {
                    comment.setCommentText(text);
                    db.updateMeta(session, comment);
                }
            }
        }
        return 0;
    }

    public void setCommentExpression(Expression expr) {
        this.expr = expr;
    }

    public void setObjectName(String objectName) {
        this.objectName = objectName;
    }

    public void setObjectType(DbObjectType objectType) {
        this.objectType = objectType;
    }

    public void setColumnName(String columnName) {
        this.columnName = columnName;
    }

    public void setSchemaName(String schemaName) {
        this.schemaName = schemaName;
    }

    public void setColumn(boolean column) {
        this.column = column;
    }

}
