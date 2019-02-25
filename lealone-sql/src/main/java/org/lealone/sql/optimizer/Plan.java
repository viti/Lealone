/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.sql.optimizer;

import java.util.ArrayList;
import java.util.HashMap;

import org.lealone.db.ServerSession;
import org.lealone.sql.expression.Expression;
import org.lealone.sql.expression.ExpressionVisitor;
import org.lealone.sql.optimizer.TableFilter.TableFilterVisitor;

/**
 * A possible query execution plan. The time required to execute a query depends
 * on the order the tables are accessed.
 */
public class Plan {

    private final TableFilter[] filters;
    private final HashMap<TableFilter, PlanItem> planItems = new HashMap<>();
    private final Expression[] allConditions;
    private final TableFilter[] allFilters;

    /**
     * Create a query plan with the given order.
     *
     * @param filters the tables of the query
     * @param count the number of table items
     * @param condition the condition in the WHERE clause
     */
    public Plan(TableFilter[] filters, int count, Expression condition) {
        this.filters = new TableFilter[count];
        System.arraycopy(filters, 0, this.filters, 0, count);
        final ArrayList<Expression> allCond = new ArrayList<>();
        final ArrayList<TableFilter> all = new ArrayList<>();
        if (condition != null) {
            allCond.add(condition);
        }
        for (int i = 0; i < count; i++) {
            TableFilter f = filters[i];
            f.visit(new TableFilterVisitor() {
                @Override
                public void accept(TableFilter f) {
                    all.add(f);
                    if (f.getJoinCondition() != null) {
                        allCond.add(f.getJoinCondition());
                    }
                }
            });
        }
        allConditions = new Expression[allCond.size()];
        allCond.toArray(allConditions);
        allFilters = new TableFilter[all.size()];
        all.toArray(allFilters);
    }

    /**
     * Get the plan item for the given table.
     *
     * @param filter the table
     * @return the plan item
     */
    public PlanItem getItem(TableFilter filter) {
        return planItems.get(filter);
    }

    /**
     * The the list of tables.
     *
     * @return the list of tables
     */
    public TableFilter[] getFilters() {
        return filters;
    }

    /**
     * Remove all index conditions that can not be used.
     */
    public void removeUnusableIndexConditions() {
        for (int i = 0; i < allFilters.length; i++) {
            TableFilter f = allFilters[i];
            setEvaluatable(f, true);
            if (i < allFilters.length - 1 || f.getSession().getDatabase().getSettings().earlyFilter) {
                // the last table doesn't need the optimization,
                // otherwise the expression is calculated twice unnecessarily
                // (not that bad but not optimal)
                f.optimizeFullCondition(false);
            }
            f.removeUnusableIndexConditions();
        }
        for (TableFilter f : allFilters) {
            setEvaluatable(f, false);
        }
    }

    /**
     * Calculate the cost of this query plan.
     *
     * @param session the session
     * @return the cost
     */
    public double calculateCost(ServerSession session) {
        double cost = 1;
        boolean invalidPlan = false;
        int level = 1;
        for (TableFilter tableFilter : allFilters) {
            PlanItem item = tableFilter.getBestPlanItem(session, level++);
            planItems.put(tableFilter, item);
            cost += cost * item.cost;
            setEvaluatable(tableFilter, true);
            Expression on = tableFilter.getJoinCondition();
            if (on != null) {
                if (!on.isEverything(ExpressionVisitor.EVALUATABLE_VISITOR)) {
                    invalidPlan = true;
                    break;
                }
            }
        }
        if (invalidPlan) {
            cost = Double.POSITIVE_INFINITY;
        }
        for (TableFilter f : allFilters) {
            setEvaluatable(f, false);
        }
        return cost;
    }

    private void setEvaluatable(TableFilter filter, boolean b) {
        filter.setEvaluatable(filter, b);
        for (Expression e : allConditions) {
            e.setEvaluatable(filter, b);
        }
    }
}
