/*
 * Copyright 2011 The Apache Software Foundation
 *
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
package org.h2.jdbc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.hadoop.hbase.util.Threads;
import org.h2.command.CommandInterface;
import org.h2.command.CommandRemote;
import org.h2.command.dml.Select;
import org.h2.engine.Session;
import org.h2.expression.ParameterInterface;
import org.h2.result.ResultInterface;
import org.h2.util.HBaseUtils;

public class HBaseJdbcSelectCommand implements CommandInterface {
    Select select;
    byte[] tableName;
    byte[] start;
    byte[] end;
    JdbcConnection jdbcConn;
    String sql;
    int fetchSize;
    Session session;

    private List<CommandInterface> commands;
    private ThreadPoolExecutor pool;
    //如果是false，那么按org.apache.hadoop.hbase.client.ClientScanner的功能来实现
    //只要Select语句中出现聚合函数、groupBy、Having三者之一都被认为是GroupQuery。
    //对于GroupQuery需要把Select语句同时发给相关的RegionServer，得到结果后再在client一起合并。
    private boolean isGroupQuery = false;

    HBaseJdbcSelectCommand() {
    }

    CommandInterface init() {
        isGroupQuery = select.isGroupQuery();
        List<byte[]> startKeys;
        try {
            //TODO 使用pool并发执行sql
            if (isGroupQuery) {
                this.pool = new ThreadPoolExecutor(1, 20, 5, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(),
                        Threads.newDaemonThreadFactory("HBaseJdbcGroupQueryCommand"));
                ((ThreadPoolExecutor) this.pool).allowCoreThreadTimeOut(true);
                startKeys = HBaseUtils.getStartKeysInRange(tableName, start, end);
            } else {
                startKeys = new ArrayList<byte[]>(1);
                startKeys.add(start);
            }

            if (startKeys != null && startKeys.size() == 1) {
                byte[] startKey = startKeys.get(0);
                JdbcConnection conn = jdbcConn.getNewConnection(startKey);
                CommandInterface c = conn.prepareCommand(conn.getNewSQL(select, startKey, false), fetchSize);
                if (c instanceof CommandRemote)
                    ((CommandRemote) c).setSelect(select);
                return c;
            }

            if (startKeys != null && startKeys.size() > 0) {
                commands = new ArrayList<CommandInterface>();
                for (byte[] startKey : startKeys) {
                    JdbcConnection conn = jdbcConn.getNewConnection(startKey);
                    commands.add(conn.prepareCommand(conn.getNewSQL(select, startKey, true), fetchSize));
                }
            }
            return this;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public String toString() {
        return sql;
    }

    @Override
    public int getCommandType() {
        return UNKNOWN;
    }

    @Override
    public boolean isQuery() {
        return true;
    }

    @Override
    public ArrayList<? extends ParameterInterface> getParameters() {
        if (commands != null)
            for (CommandInterface c : commands)
                return c.getParameters();
        return null;
    }

    @Override
    public ResultInterface executeQuery(int maxRows, boolean scrollable) {
        if (commands != null) {
            List<ResultInterface> results = new ArrayList<ResultInterface>(commands.size());
            for (CommandInterface c : commands) {
                ResultInterface result = c.executeQuery(maxRows, scrollable);
                if (result != null)
                    results.add(result);
            }
            String newSQL = select.getPlanSQL(null, null, true);
            Select newSelect = (Select) session.prepare(newSQL, true);
            return new HBaseJdbcSelectResult(results, newSelect, select, isGroupQuery);
        }
        return null;
    }

    @Override
    public int executeUpdate() {
        return 0;
    }

    @Override
    public void close() {
        if (commands != null)
            for (CommandInterface c : commands)
                c.close();
    }

    @Override
    public void cancel() {
        if (commands != null)
            for (CommandInterface c : commands)
                c.cancel();
    }

    @Override
    public ResultInterface getMetaData() {
        if (commands != null)
            for (CommandInterface c : commands)
                return c.getMetaData();
        return null;
    }
}