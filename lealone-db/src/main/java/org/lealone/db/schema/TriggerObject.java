/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.db.schema;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;

import org.lealone.common.exceptions.DbException;
import org.lealone.common.trace.Trace;
import org.lealone.common.util.StatementBuilder;
import org.lealone.common.util.StringUtils;
import org.lealone.common.util.Utils;
import org.lealone.db.Constants;
import org.lealone.db.DbObjectType;
import org.lealone.db.ServerSession;
import org.lealone.db.api.ErrorCode;
import org.lealone.db.api.Trigger;
import org.lealone.db.result.Row;
import org.lealone.db.table.Table;
import org.lealone.db.util.SourceCompiler;
import org.lealone.db.value.DataType;
import org.lealone.db.value.Value;

/**
 * A trigger is created using the statement
 * CREATE TRIGGER
 */
public class TriggerObject extends SchemaObjectBase {

    /**
     * The default queue size.
     */
    public static final int DEFAULT_QUEUE_SIZE = 1024;

    private boolean insteadOf;
    private boolean before;
    private int typeMask;
    private boolean rowBased;
    private boolean onRollback;
    // TODO trigger: support queue and noWait = false as well
    private int queueSize = DEFAULT_QUEUE_SIZE;
    private boolean noWait;
    private Table table;
    private String triggerClassName;
    private String triggerSource;
    private Trigger triggerCallback;

    public TriggerObject(Schema schema, int id, String name, Table table) {
        super(schema, id, name, Trace.TRIGGER);
        this.table = table;
        setTemporary(table.isTemporary());
    }

    @Override
    public DbObjectType getType() {
        return DbObjectType.TRIGGER;
    }

    public void setBefore(boolean before) {
        this.before = before;
    }

    public void setInsteadOf(boolean insteadOf) {
        this.insteadOf = insteadOf;
    }

    private synchronized void load() {
        if (triggerCallback != null) {
            return;
        }
        try {
            ServerSession sysSession = database.getSystemSession();
            Connection c2 = sysSession.createConnection(false);
            Object obj;
            if (triggerClassName != null) {
                obj = Utils.loadUserClass(triggerClassName).newInstance();
            } else {
                obj = loadFromSource();
            }
            triggerCallback = (Trigger) obj;
            triggerCallback.init(c2, getSchema().getName(), getName(), table.getName(), before, typeMask);
        } catch (Throwable e) {
            // try again later
            triggerCallback = null;
            throw DbException.get(ErrorCode.ERROR_CREATING_TRIGGER_OBJECT_3, e, getName(),
                    triggerClassName != null ? triggerClassName : "..source..", e.toString());
        }
    }

    private Trigger loadFromSource() {
        SourceCompiler compiler = database.getCompiler();
        synchronized (compiler) {
            String fullClassName = Constants.USER_PACKAGE + ".trigger." + getName();
            compiler.setSource(fullClassName, triggerSource);
            try {
                Method m = compiler.getMethod(fullClassName);
                if (m.getParameterTypes().length > 0) {
                    throw new IllegalStateException("No parameters are allowed for a trigger");
                }
                return (Trigger) m.invoke(null);
            } catch (DbException e) {
                throw e;
            } catch (Exception e) {
                throw DbException.get(ErrorCode.SYNTAX_ERROR_1, e, triggerSource);
            }
        }
    }

    /**
     * Set the trigger class name and load the class if possible.
     *
     * @param triggerClassName the name of the trigger class
     * @param force whether exceptions (due to missing class or access rights)
     *            should be ignored
     */
    public void setTriggerClassName(String triggerClassName, boolean force) {
        this.setTriggerAction(triggerClassName, null, force);
    }

    /**
     * Set the trigger source code and compile it if possible.
     *
     * @param source the source code of a method returning a {@link Trigger}
     * @param force whether exceptions (due to syntax error)
     *            should be ignored
     */
    public void setTriggerSource(String source, boolean force) {
        this.setTriggerAction(null, source, force);
    }

    private void setTriggerAction(String triggerClassName, String source, boolean force) {
        this.triggerClassName = triggerClassName;
        this.triggerSource = source;
        try {
            load();
        } catch (DbException e) {
            if (!force) {
                throw e;
            }
        }
    }

    /**
     * Call the trigger class if required. This method does nothing if the
     * trigger is not defined for the given action. This method is called before
     * or after any rows have been processed, once for each statement.
     *
     * @param session the session
     * @param type the trigger type
     * @param beforeAction if this method is called before applying the changes
     */
    public void fire(ServerSession session, int type, boolean beforeAction) {
        if (rowBased || before != beforeAction || (typeMask & type) == 0) {
            return;
        }
        load();
        Connection c2 = session.createConnection(false);
        boolean old = false;
        if (type != Trigger.SELECT) {
            old = session.setCommitOrRollbackDisabled(true);
        }
        Value identity = session.getLastScopeIdentity();
        try {
            triggerCallback.fire(c2, null, null);
        } catch (Throwable e) {
            throw DbException.get(ErrorCode.ERROR_EXECUTING_TRIGGER_3, e, getName(),
                    triggerClassName != null ? triggerClassName : "..source..", e.toString());
        } finally {
            session.setLastScopeIdentity(identity);
            if (type != Trigger.SELECT) {
                session.setCommitOrRollbackDisabled(old);
            }
        }
    }

    private static Object[] convertToObjectList(Row row) {
        if (row == null) {
            return null;
        }
        int len = row.getColumnCount();
        Object[] list = new Object[len];
        for (int i = 0; i < len; i++) {
            list[i] = row.getValue(i).getObject();
        }
        return list;
    }

    /**
     * Call the fire method of the user-defined trigger class if required. This
     * method does nothing if the trigger is not defined for the given action.
     * This method is called before or after a row is processed, possibly many
     * times for each statement.
     *
     * @param session the session
     * @param oldRow the old row
     * @param newRow the new row
     * @param beforeAction true if this method is called before the operation is
     *            applied
     * @param rollback when the operation occurred within a rollback
     * @return true if no further action is required (for 'instead of' triggers)
     */
    public boolean fireRow(ServerSession session, Row oldRow, Row newRow, boolean beforeAction, boolean rollback) {
        if (!rowBased || before != beforeAction) {
            return false;
        }
        if (rollback && !onRollback) {
            return false;
        }
        load();
        Object[] oldList;
        Object[] newList;
        boolean fire = false;
        if ((typeMask & Trigger.INSERT) != 0) {
            if (oldRow == null && newRow != null) {
                fire = true;
            }
        }
        if ((typeMask & Trigger.UPDATE) != 0) {
            if (oldRow != null && newRow != null) {
                fire = true;
            }
        }
        if ((typeMask & Trigger.DELETE) != 0) {
            if (oldRow != null && newRow == null) {
                fire = true;
            }
        }
        if (!fire) {
            return false;
        }
        oldList = convertToObjectList(oldRow);
        newList = convertToObjectList(newRow);
        Object[] newListBackup;
        if (before && newList != null) {
            newListBackup = new Object[newList.length];
            System.arraycopy(newList, 0, newListBackup, 0, newList.length);
        } else {
            newListBackup = null;
        }
        Connection c2 = session.createConnection(false);
        boolean old = session.isAutoCommit();
        boolean oldDisabled = session.setCommitOrRollbackDisabled(true);
        Value identity = session.getLastScopeIdentity();
        try {
            session.setAutoCommit(false);
            triggerCallback.fire(c2, oldList, newList);
            if (newListBackup != null) {
                for (int i = 0; i < newList.length; i++) {
                    Object o = newList[i];
                    if (o != newListBackup[i]) {
                        Value v = DataType.convertToValue(session, o, Value.UNKNOWN);
                        newRow.setValue(i, v);
                    }
                }
            }
        } catch (Exception e) {
            if (onRollback) {
                // ignore
            } else {
                throw DbException.convert(e);
            }
        } finally {
            session.setLastScopeIdentity(identity);
            session.setCommitOrRollbackDisabled(oldDisabled);
            session.setAutoCommit(old);
        }
        return insteadOf;
    }

    /**
     * Set the trigger type.
     *
     * @param typeMask the type
     */
    public void setTypeMask(int typeMask) {
        this.typeMask = typeMask;
    }

    public void setRowBased(boolean rowBased) {
        this.rowBased = rowBased;
    }

    public void setQueueSize(int size) {
        this.queueSize = size;
    }

    public int getQueueSize() {
        return queueSize;
    }

    public void setNoWait(boolean noWait) {
        this.noWait = noWait;
    }

    public boolean isNoWait() {
        return noWait;
    }

    public void setOnRollback(boolean onRollback) {
        this.onRollback = onRollback;
    }

    public String getTypeNameList() {
        StatementBuilder buff = new StatementBuilder();
        if ((typeMask & Trigger.INSERT) != 0) {
            buff.appendExceptFirst(", ");
            buff.append("INSERT");
        }
        if ((typeMask & Trigger.UPDATE) != 0) {
            buff.appendExceptFirst(", ");
            buff.append("UPDATE");
        }
        if ((typeMask & Trigger.DELETE) != 0) {
            buff.appendExceptFirst(", ");
            buff.append("DELETE");
        }
        if ((typeMask & Trigger.SELECT) != 0) {
            buff.appendExceptFirst(", ");
            buff.append("SELECT");
        }
        if (onRollback) {
            buff.appendExceptFirst(", ");
            buff.append("ROLLBACK");
        }
        return buff.toString();
    }

    @Override
    public String getCreateSQL() {
        StringBuilder buff = new StringBuilder("CREATE FORCE TRIGGER ");
        buff.append(getSQL());
        if (insteadOf) {
            buff.append(" INSTEAD OF ");
        } else if (before) {
            buff.append(" BEFORE ");
        } else {
            buff.append(" AFTER ");
        }
        buff.append(getTypeNameList());
        buff.append(" ON ").append(table.getSQL());
        if (rowBased) {
            buff.append(" FOR EACH ROW");
        }
        if (noWait) {
            buff.append(" NOWAIT");
        } else {
            buff.append(" QUEUE ").append(queueSize);
        }
        if (triggerClassName != null) {
            buff.append(" CALL ").append(database.quoteIdentifier(triggerClassName));
        } else {
            buff.append(" AS ").append(StringUtils.quoteStringSQL(triggerSource));
        }
        return buff.toString();
    }

    @Override
    public void removeChildrenAndResources(ServerSession session) {
        table.removeTrigger(this);
        database.removeMeta(session, getId());
        if (triggerCallback != null) {
            try {
                triggerCallback.remove();
            } catch (SQLException e) {
                throw DbException.convert(e);
            }
        }
        table = null;
        triggerClassName = null;
        triggerSource = null;
        triggerCallback = null;
        invalidate();
    }

    /**
     * Get the table of this trigger.
     *
     * @return the table
     */
    public Table getTable() {
        return table;
    }

    /**
     * Check if this is a before trigger.
     *
     * @return true if it is
     */
    public boolean isBefore() {
        return before;
    }

    /**
     * Get the trigger class name.
     *
     * @return the class name
     */
    public String getTriggerClassName() {
        return triggerClassName;
    }

    public String getTriggerSource() {
        return triggerSource;
    }

    /**
     * Close the trigger.
     */
    public void close() throws SQLException {
        if (triggerCallback != null) {
            triggerCallback.close();
        }
    }

    /**
     * Check whether this is a select trigger.
     *
     * @return true if it is
     */
    public boolean isSelectTrigger() {
        return (typeMask & Trigger.SELECT) != 0;
    }

}
