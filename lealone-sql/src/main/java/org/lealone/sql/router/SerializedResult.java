/*
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
package org.lealone.sql.router;

import java.util.List;
import java.util.concurrent.Callable;

import org.lealone.common.exceptions.DbException;
import org.lealone.db.result.DelegatedResult;
import org.lealone.db.result.Result;

public class SerializedResult extends DelegatedResult {
    private final static int UNKNOW_ROW_COUNT = -1;
    private final List<Result> results;
    private final List<Callable<Result>> commands;
    // private final int maxRows;
    private final int limitRows;
    // private final boolean scrollable;

    private final int size;
    private int index = 0;
    private int count = 0;

    public SerializedResult(List<Callable<Result>> commands, int maxRows, boolean scrollable, int limitRows) {
        this.results = null;
        this.commands = commands;
        // this.maxRows = maxRows;
        this.limitRows = limitRows;
        // this.scrollable = scrollable;
        this.size = commands.size();
        nextResult();
    }

    public SerializedResult(List<Result> results, int limitRows) {
        this.results = results;
        this.commands = null;
        // this.maxRows = -1;
        this.limitRows = limitRows;
        // this.scrollable = false;
        this.size = results.size();
        nextResult();
    }

    private boolean nextResult() {
        if (index >= size)
            return false;

        if (result != null)
            result.close();

        if (results != null)
            result = results.get(index++);
        else
            try {
                result = commands.get(index++).call();
            } catch (Exception e) {
                throw DbException.convert(e);
            }
        return true;
    }

    @Override
    public boolean next() {
        count++;
        if (limitRows >= 0 && count > limitRows)
            return false;
        boolean next = result.next();
        if (!next) {
            boolean nextResult;
            while (true) {
                nextResult = nextResult();
                if (nextResult) {
                    next = result.next();
                    if (next)
                        return true;
                } else
                    return false;
            }
        }

        return next;
    }

    @Override
    public int getRowCount() {
        return UNKNOW_ROW_COUNT;
    }
}
