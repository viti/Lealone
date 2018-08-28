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
package org.lealone.db;

import java.util.List;

import org.lealone.api.ErrorCode;
import org.lealone.common.exceptions.DbException;
import org.lealone.common.util.MathUtils;
import org.lealone.db.auth.User;
import org.lealone.net.NetEndpoint;

/**
 * The engine is responsible for creating new sessions.
 * This is a singleton class.
 * 
 * @author H2 Group
 * @author zhh
 */
public class DatabaseEngine {

    private static final ServerSessionFactory SESSION_FACTORY = new ServerSessionFactory();

    public static List<Database> getDatabases() {
        return LealoneDatabase.getInstance().getDatabases();
    }

    public static SessionFactory getSessionFactory() {
        return SESSION_FACTORY;
    }

    public static ServerSession createSession(String url) {
        return SESSION_FACTORY.createSession(new ConnectionInfo(url));
    }

    public static ServerSession createSession(ConnectionInfo ci) {
        return SESSION_FACTORY.createSession(ci);
    }

    private DatabaseEngine() {
    }

    private static class ServerSessionFactory implements SessionFactory {

        private volatile long wrongPasswordDelay = SysProperties.DELAY_WRONG_PASSWORD_MIN;

        @Override
        public ServerSession createSession(ConnectionInfo ci) {
            String dbName = ci.getDatabaseShortName();
            // 内嵌数据库，如果不存在，则自动创建
            if (ci.isEmbedded() && LealoneDatabase.getInstance().findDatabase(dbName) == null) {
                LealoneDatabase.getInstance().createEmbeddedDatabase(dbName, ci);
            }
            try {
                ServerSession session;
                for (int i = 0;; i++) {
                    session = createSession(dbName, ci);
                    if (session != null) {
                        break;
                    }
                    // we found a database that is currently closing
                    // wait a bit to avoid a busy loop (the method is synchronized)
                    if (i > 60 * 1000) {
                        // retry at most 1 minute
                        throw DbException.get(ErrorCode.DATABASE_ALREADY_OPEN_1,
                                "Waited for database closing longer than 1 minute");
                    }
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        // ignore
                    }
                }
                if (session.isInvalid()) { // 无效session，不需要进行后续的操作
                    return session;
                }

                initSession(session, ci);
                validateUserAndPassword(true);
                return session;
            } catch (DbException e) {
                if (e.getErrorCode() == ErrorCode.WRONG_USER_OR_PASSWORD) {
                    validateUserAndPassword(false);
                }
                throw e;
            }
        }

        private ServerSession createSession(String dbName, ConnectionInfo ci) {
            Database database = LealoneDatabase.getInstance().getDatabase(dbName);
            String targetEndpoints;
            if (ci.isEmbedded()) {
                targetEndpoints = null;
            } else {
                NetEndpoint localEndpoint = NetEndpoint.getLocalTcpEndpoint();
                targetEndpoints = database.getTargetEndpoints();
                // 为null时总是认为当前节点就是数据库所在的节点
                if (targetEndpoints == null) {
                    targetEndpoints = localEndpoint.getHostAndPort();
                } else if (!database.isTargetEndpoint(localEndpoint)) {
                    ServerSession session = new ServerSession(database,
                            LealoneDatabase.getInstance().getSystemSession().getUser(), 0);
                    session.setTargetEndpoints(targetEndpoints);
                    session.setRunMode(database.getRunMode());
                    session.setInvalid(true);
                    return session;
                }
            }

            if (!database.isInitialized()) {
                database.init();
            }
            if (database.isClosing()) {
                return null;
            }

            User user = null;
            if (database.validateFilePasswordHash(ci.getProperty("CIPHER", null), ci.getFilePasswordHash())) {
                user = database.findUser(ci.getUserName());
                if (user != null) {
                    if (!user.validateUserPasswordHash(ci.getUserPasswordHash())) {
                        user = null;
                    } else {
                        database.setLastConnectionInfo(ci);
                    }
                }
            }
            if (user == null) {
                database.removeSession(null);
                throw DbException.get(ErrorCode.WRONG_USER_OR_PASSWORD);
            }
            ServerSession session = database.createSession(user);
            session.setConnectionInfo(ci);
            session.setTargetEndpoints(targetEndpoints);
            session.setRunMode(database.getRunMode());
            return session;
        }

        private void initSession(ServerSession session, ConnectionInfo ci) {
            if (ci.getProperty("IS_LOCAL") != null)
                session.setLocal(Boolean.parseBoolean(ci.getProperty("IS_LOCAL")));

            boolean ignoreUnknownSetting = ci.getProperty("IGNORE_UNKNOWN_SETTINGS", false);
            String init = ci.getProperty("INIT", null);

            session.setAllowLiterals(true);
            for (String setting : ci.getKeys()) {
                if (SetTypes.contains(setting)) {
                    String value = ci.getProperty(setting);
                    try {
                        String sql = "SET " + session.getDatabase().quoteIdentifier(setting) + " " + value;
                        session.prepareStatementLocal(sql).executeUpdate();
                    } catch (DbException e) {
                        if (!ignoreUnknownSetting) {
                            session.close();
                            throw e;
                        }
                    }
                }
            }
            if (init != null) {
                try {
                    session.prepareStatement(init, Integer.MAX_VALUE).executeUpdate();
                } catch (DbException e) {
                    if (!ignoreUnknownSetting) {
                        session.close();
                        throw e;
                    }
                }
            }
            session.setAllowLiterals(false);
            session.commit();
        }

        /**
         * This method is called after validating user name and password. If user
         * name and password were correct, the sleep time is reset, otherwise this
         * method waits some time (to make brute force / rainbow table attacks
         * harder) and then throws a 'wrong user or password' exception. The delay
         * is a bit randomized to protect against timing attacks. Also the delay
         * doubles after each unsuccessful logins, to make brute force attacks
         * harder.
         *
         * There is only one exception message both for wrong user and for
         * wrong password, to make it harder to get the list of user names. This
         * method must only be called from one place, so it is not possible from the
         * stack trace to see if the user name was wrong or the password.
         *
         * @param correct if the user name or the password was correct
         * @throws DbException the exception 'wrong user or password'
         */
        private void validateUserAndPassword(boolean correct) {
            int min = SysProperties.DELAY_WRONG_PASSWORD_MIN;
            if (correct) {
                long delay = wrongPasswordDelay;
                if (delay > min && delay > 0) {
                    // the first correct password must be blocked,
                    // otherwise parallel attacks are possible
                    synchronized (this) {
                        // delay up to the last delay
                        // an attacker can't know how long it will be
                        delay = MathUtils.secureRandomInt((int) delay);
                        try {
                            Thread.sleep(delay);
                        } catch (InterruptedException e) {
                            // ignore
                        }
                        wrongPasswordDelay = min;
                    }
                }
            } else {
                // this method is not synchronized on the Engine, so that
                // regular successful attempts are not blocked
                synchronized (this) {
                    long delay = wrongPasswordDelay;
                    int max = SysProperties.DELAY_WRONG_PASSWORD_MAX;
                    if (max <= 0) {
                        max = Integer.MAX_VALUE;
                    }
                    wrongPasswordDelay += wrongPasswordDelay;
                    if (wrongPasswordDelay > max || wrongPasswordDelay < 0) {
                        wrongPasswordDelay = max;
                    }
                    if (min > 0) {
                        // a bit more to protect against timing attacks
                        delay += Math.abs(MathUtils.secureRandomLong() % 100);
                        try {
                            Thread.sleep(delay);
                        } catch (InterruptedException e) {
                            // ignore
                        }
                    }
                    throw DbException.get(ErrorCode.WRONG_USER_OR_PASSWORD);
                }
            }
        }
    }
}
