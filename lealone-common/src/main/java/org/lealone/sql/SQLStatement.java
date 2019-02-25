/*
 * Copyright 2004-2014 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.lealone.sql;

import org.lealone.db.Command;

/**
 * Represents a SQL statement.
 * 
 * @author H2 Group
 * @author zhh
 */
// 此接口只定义了所有子类对应的类型常量值
public interface SQLStatement extends Command {

    /**
     * The type for unknown statement.
     */
    int UNKNOWN = 0;

    // ddl operations

    /**
     * The type of a ALTER INDEX RENAME statement.
     */
    int ALTER_INDEX_RENAME = 1;

    /**
     * The type of a ALTER SCHEMA RENAME statement.
     */
    int ALTER_SCHEMA_RENAME = 2;

    /**
     * The type of a ALTER TABLE ADD CHECK statement.
     */
    int ALTER_TABLE_ADD_CONSTRAINT_CHECK = 3;

    /**
     * The type of a ALTER TABLE ADD UNIQUE statement.
     */
    int ALTER_TABLE_ADD_CONSTRAINT_UNIQUE = 4;

    /**
     * The type of a ALTER TABLE ADD FOREIGN KEY statement.
     */
    int ALTER_TABLE_ADD_CONSTRAINT_REFERENTIAL = 5;

    /**
     * The type of a ALTER TABLE ADD PRIMARY KEY statement.
     */
    int ALTER_TABLE_ADD_CONSTRAINT_PRIMARY_KEY = 6;

    /**
     * The type of a ALTER TABLE ADD statement.
     */
    int ALTER_TABLE_ADD_COLUMN = 7;

    /**
     * The type of a ALTER TABLE ALTER COLUMN SET NOT NULL statement.
     */
    int ALTER_TABLE_ALTER_COLUMN_NOT_NULL = 8;

    /**
     * The type of a ALTER TABLE ALTER COLUMN SET NULL statement.
     */
    int ALTER_TABLE_ALTER_COLUMN_NULL = 9;

    /**
     * The type of a ALTER TABLE ALTER COLUMN SET DEFAULT statement.
     */
    int ALTER_TABLE_ALTER_COLUMN_DEFAULT = 10;

    /**
     * The type of an ALTER TABLE ALTER COLUMN statement that changes the column
     * data type.
     */
    int ALTER_TABLE_ALTER_COLUMN_CHANGE_TYPE = 11;

    /**
     * The type of a ALTER TABLE DROP COLUMN statement.
     */
    int ALTER_TABLE_DROP_COLUMN = 12;

    /**
     * The type of a ALTER TABLE ALTER COLUMN SELECTIVITY statement.
     */
    int ALTER_TABLE_ALTER_COLUMN_SELECTIVITY = 13;

    /**
     * The type of a ALTER TABLE DROP CONSTRAINT statement.
     */
    int ALTER_TABLE_DROP_CONSTRAINT = 14;

    /**
     * The type of a ALTER TABLE RENAME statement.
     */
    int ALTER_TABLE_RENAME = 15;

    /**
     * The type of a ALTER TABLE ALTER COLUMN RENAME statement.
     */
    int ALTER_TABLE_ALTER_COLUMN_RENAME = 16;

    /**
     * The type of a ALTER USER ADMIN statement.
     */
    int ALTER_USER_ADMIN = 17;

    /**
     * The type of a ALTER USER RENAME statement.
     */
    int ALTER_USER_RENAME = 18;

    /**
     * The type of a ALTER USER SET PASSWORD statement.
     */
    int ALTER_USER_SET_PASSWORD = 19;

    /**
     * The type of a ALTER VIEW statement.
     */
    int ALTER_VIEW = 20;

    /**
     * The type of a ANALYZE statement.
     */
    int ANALYZE = 21;

    /**
     * The type of a CREATE AGGREGATE statement.
     */
    int CREATE_AGGREGATE = 22;

    /**
     * The type of a CREATE CONSTANT statement.
     */
    int CREATE_CONSTANT = 23;

    /**
     * The type of a CREATE ALIAS statement.
     */
    int CREATE_ALIAS = 24;

    /**
     * The type of a CREATE INDEX statement.
     */
    int CREATE_INDEX = 25;

    /**
     * The type of a CREATE LINKED TABLE statement.
     */
    int CREATE_LINKED_TABLE = 26;

    /**
     * The type of a CREATE ROLE statement.
     */
    int CREATE_ROLE = 27;

    /**
     * The type of a CREATE SCHEMA statement.
     */
    int CREATE_SCHEMA = 28;

    /**
     * The type of a CREATE SEQUENCE statement.
     */
    int CREATE_SEQUENCE = 29;

    /**
     * The type of a CREATE TABLE statement.
     */
    int CREATE_TABLE = 30;

    /**
     * The type of a CREATE TRIGGER statement.
     */
    int CREATE_TRIGGER = 31;

    /**
     * The type of a CREATE USER statement.
     */
    int CREATE_USER = 32;

    /**
     * The type of a CREATE DOMAIN statement.
     */
    int CREATE_DOMAIN = 33;

    /**
     * The type of a CREATE VIEW statement.
     */
    int CREATE_VIEW = 34;

    /**
     * The type of a DEALLOCATE statement.
     */
    int DEALLOCATE = 35;

    /**
     * The type of a DROP AGGREGATE statement.
     */
    int DROP_AGGREGATE = 36;

    /**
     * The type of a DROP CONSTANT statement.
     */
    int DROP_CONSTANT = 37;

    /**
     * The type of a DROP DATABASE statement.
     */
    int DROP_DATABASE = 38;

    /**
     * The type of a DROP ALIAS statement.
     */
    int DROP_ALIAS = 39;

    /**
     * The type of a DROP INDEX statement.
     */
    int DROP_INDEX = 40;

    /**
     * The type of a DROP ROLE statement.
     */
    int DROP_ROLE = 41;

    /**
     * The type of a DROP SCHEMA statement.
     */
    int DROP_SCHEMA = 42;

    /**
     * The type of a DROP SEQUENCE statement.
     */
    int DROP_SEQUENCE = 43;

    /**
     * The type of a DROP TABLE statement.
     */
    int DROP_TABLE = 44;

    /**
     * The type of a DROP TRIGGER statement.
     */
    int DROP_TRIGGER = 45;

    /**
     * The type of a DROP USER statement.
     */
    int DROP_USER = 46;

    /**
     * The type of a DROP DOMAIN statement.
     */
    int DROP_DOMAIN = 47;

    /**
     * The type of a DROP VIEW statement.
     */
    int DROP_VIEW = 48;

    /**
     * The type of a GRANT statement.
     */
    int GRANT = 49;

    /**
     * The type of a REVOKE statement.
     */
    int REVOKE = 50;

    /**
     * The type of a PREPARE statement.
     */
    int PREPARE = 51;

    /**
     * The type of a COMMENT statement.
     */
    int COMMENT = 52;

    /**
     * The type of a TRUNCATE TABLE statement.
     */
    int TRUNCATE_TABLE = 53;

    // dml operations

    /**
     * The type of a ALTER SEQUENCE statement.
     */
    int ALTER_SEQUENCE = 54;

    /**
     * The type of a ALTER TABLE SET REFERENTIAL_INTEGRITY statement.
     */
    int ALTER_TABLE_SET_REFERENTIAL_INTEGRITY = 55;

    /**
     * The type of a BACKUP statement.
     */
    int BACKUP = 56;

    /**
     * The type of a CALL statement.
     */
    int CALL = 57;

    /**
     * The type of a DELETE statement.
     */
    int DELETE = 58;

    /**
     * The type of a EXECUTE statement.
     */
    int EXECUTE = 59;

    /**
     * The type of a EXPLAIN statement.
     */
    int EXPLAIN = 60;

    /**
     * The type of a INSERT statement.
     */
    int INSERT = 61;

    /**
     * The type of a MERGE statement.
     */
    int MERGE = 62;

    /**
     * The type of a no operation statement.
     */
    int NO_OPERATION = 63;

    /**
     * The type of a RUNSCRIPT statement.
     */
    int RUNSCRIPT = 64;

    /**
     * The type of a SCRIPT statement.
     */
    int SCRIPT = 65;

    /**
     * The type of a SELECT statement.
     */
    int SELECT = 66;

    /**
     * The type of a SET statement.
     */
    int SET = 67;

    /**
     * The type of a UPDATE statement.
     */
    int UPDATE = 68;

    // transaction commands

    /**
     * The type of a SET AUTOCOMMIT statement.
     */
    int SET_AUTOCOMMIT_TRUE = 69;

    /**
     * The type of a SET AUTOCOMMIT statement.
     */
    int SET_AUTOCOMMIT_FALSE = 70;

    /**
     * The type of a COMMIT statement.
     */
    int COMMIT = 71;

    /**
     * The type of a ROLLBACK statement.
     */
    int ROLLBACK = 72;

    /**
     * The type of a CHECKPOINT statement.
     */
    int CHECKPOINT = 73;

    /**
     * The type of a SAVEPOINT statement.
     */
    int SAVEPOINT = 74;

    /**
     * The type of a ROLLBACK TO SAVEPOINT statement.
     */
    int ROLLBACK_TO_SAVEPOINT = 75;

    /**
     * The type of a CHECKPOINT SYNC statement.
     */
    int CHECKPOINT_SYNC = 76;

    /**
     * The type of a PREPARE COMMIT statement.
     */
    int PREPARE_COMMIT = 77;

    /**
     * The type of a COMMIT TRANSACTION statement.
     */
    int COMMIT_TRANSACTION = 78;

    /**
     * The type of a ROLLBACK TRANSACTION statement.
     */
    int ROLLBACK_TRANSACTION = 79;

    /**
     * The type of a SHUTDOWN statement.
     */
    int SHUTDOWN = 80;

    /**
     * The type of a SHUTDOWN IMMEDIATELY statement.
     */
    int SHUTDOWN_IMMEDIATELY = 81;

    /**
     * The type of a SHUTDOWN COMPACT statement.
     */
    int SHUTDOWN_COMPACT = 82;

    /**
     * The type of a BEGIN {WORK|TRANSACTION} statement.
     */
    int BEGIN = 83;

    /**
     * The type of a SHUTDOWN DEFRAG statement.
     */
    int SHUTDOWN_DEFRAG = 84;

    /**
     * The type of a ALTER SCHEMA WTIH REPLICATION statement.
     */
    int ALTER_SCHEMA_WTIH_REPLICATION = 85;

    int CREATE_DATABASE = 86;

    int ALTER_DATABASE = 87;

    int ADMIN = 88;

    int CREATE_SERVICE = 89;

    String INTERNAL_SAVEPOINT = "_INTERNAL_SAVEPOINT_";
}
