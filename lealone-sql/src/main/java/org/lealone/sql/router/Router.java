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

import org.lealone.db.Database;
import org.lealone.db.RunMode;
import org.lealone.db.ServerSession;
import org.lealone.db.result.Result;
import org.lealone.sql.StatementBase;

public interface Router {

    int executeUpdate(StatementBase statement);

    Result executeQuery(StatementBase statement, int maxRows);

    String[] getHostIds(Database db);

    int executeDatabaseStatement(Database db, ServerSession currentSession, StatementBase statement);

    default void replicate(Database db, RunMode oldRunMode, RunMode newRunMode, String[] newReplicationEndpoints) {
    }

    default String[] getReplicationEndpoints(Database db) {
        return new String[0];
    }

    default void sharding(Database db, RunMode oldRunMode, RunMode newRunMode, String[] oldEndpoints,
            String[] newEndpoints) {
    }

    default String[] getShardingEndpoints(Database db) {
        return new String[0];
    }
}
