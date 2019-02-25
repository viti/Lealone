/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.sql.expression.function;

import org.lealone.common.util.StatementBuilder;
import org.lealone.db.Constants;
import org.lealone.db.ServerSession;
import org.lealone.db.schema.FunctionAlias;
import org.lealone.db.value.DataType;
import org.lealone.db.value.Value;
import org.lealone.db.value.ValueArray;
import org.lealone.db.value.ValueNull;
import org.lealone.db.value.ValueResultSet;
import org.lealone.sql.Parser;
import org.lealone.sql.expression.Expression;
import org.lealone.sql.expression.ExpressionVisitor;
import org.lealone.sql.expression.ValueExpression;
import org.lealone.sql.optimizer.ColumnResolver;
import org.lealone.sql.optimizer.TableFilter;

/**
 * This class wraps a user-defined function.
 */
public class JavaFunction extends Expression implements FunctionCall {

    private final FunctionAlias functionAlias;
    private final FunctionAlias.JavaMethod javaMethod;
    private final Expression[] args;

    public JavaFunction(FunctionAlias functionAlias, Expression[] args) {
        this.functionAlias = functionAlias;
        this.javaMethod = functionAlias.findJavaMethod(args);
        this.args = args;
    }

    @Override
    public Value getValue(ServerSession session) {
        return javaMethod.getValue(session, args, false);
    }

    @Override
    public int getType() {
        return javaMethod.getDataType();
    }

    @Override
    public void mapColumns(ColumnResolver resolver, int level) {
        for (Expression e : args) {
            e.mapColumns(resolver, level);
        }
    }

    @Override
    public Expression optimize(ServerSession session) {
        boolean allConst = isDeterministic();
        for (int i = 0, len = args.length; i < len; i++) {
            Expression e = args[i].optimize(session);
            args[i] = e;
            allConst &= e.isConstant();
        }
        if (allConst) {
            return ValueExpression.get(getValue(session));
        }
        return this;
    }

    @Override
    public void setEvaluatable(TableFilter tableFilter, boolean b) {
        for (Expression e : args) {
            if (e != null) {
                e.setEvaluatable(tableFilter, b);
            }
        }
    }

    @Override
    public int getScale() {
        return DataType.getDataType(getType()).defaultScale;
    }

    @Override
    public long getPrecision() {
        return Integer.MAX_VALUE;
    }

    @Override
    public int getDisplaySize() {
        return Integer.MAX_VALUE;
    }

    @Override
    public String getSQL(boolean isDistributed) {
        StatementBuilder buff = new StatementBuilder();
        // TODO always append the schema once FUNCTIONS_IN_SCHEMA is enabled
        if (functionAlias.getDatabase().getSettings().functionsInSchema
                || !functionAlias.getSchema().getName().equals(Constants.SCHEMA_MAIN)) {
            buff.append(Parser.quoteIdentifier(functionAlias.getSchema().getName())).append('.');
        }
        buff.append(Parser.quoteIdentifier(functionAlias.getName())).append('(');
        for (Expression e : args) {
            buff.appendExceptFirst(", ");
            buff.append(e.getSQL(isDistributed));
        }
        return buff.append(')').toString();
    }

    @Override
    public void updateAggregate(ServerSession session) {
        for (Expression e : args) {
            if (e != null) {
                e.updateAggregate(session);
            }
        }
    }

    @Override
    public String getName() {
        return functionAlias.getName();
    }

    @Override
    public ValueResultSet getValueForColumnList(ServerSession session, Expression[] argList) {
        Value v = javaMethod.getValue(session, argList, true);
        return v == ValueNull.INSTANCE ? null : (ValueResultSet) v;
    }

    @Override
    public Expression[] getArgs() {
        return args;
    }

    @Override
    public boolean isEverything(ExpressionVisitor visitor) {
        switch (visitor.getType()) {
        case ExpressionVisitor.DETERMINISTIC:
            if (!isDeterministic()) {
                return false;
            }
            // only if all parameters are deterministic as well
            break;
        case ExpressionVisitor.GET_DEPENDENCIES:
            visitor.addDependency(functionAlias);
            break;
        default:
        }
        for (Expression e : args) {
            if (e != null && !e.isEverything(visitor)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int getCost() {
        int cost = javaMethod.hasConnectionParam() ? 25 : 5;
        for (Expression e : args) {
            cost += e.getCost();
        }
        return cost;
    }

    @Override
    public boolean isDeterministic() {
        return functionAlias.isDeterministic();
    }

    @Override
    public Expression[] getExpressionColumns(ServerSession session) {
        switch (getType()) {
        case Value.RESULT_SET:
            ValueResultSet rs = getValueForColumnList(session, getArgs());
            return getExpressionColumns(session, rs.getResultSet());
        case Value.ARRAY:
            return getExpressionColumns(session, (ValueArray) getValue(session));
        }
        return super.getExpressionColumns(session);
    }

    @Override
    public boolean isBufferResultSetToLocalTemp() {
        return functionAlias.isBufferResultSetToLocalTemp();
    }

}
