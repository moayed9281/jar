package com.smpcore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** SQL differences between MySQL/MariaDB, PostgreSQL and SQLite. */
public enum Dialect {
    MYSQL, POSTGRES, SQLITE;

    public static Dialect of(String type) {
        return switch (type == null ? "" : type.toLowerCase(Locale.ROOT)) {
            case "postgresql", "postgres", "pg" -> POSTGRES;
            case "sqlite" -> SQLITE;
            default -> MYSQL; // mysql and mariadb
        };
    }

    /** Current time in milliseconds, computed by the database. */
    public String now() {
        return switch (this) {
            case MYSQL -> "CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS UNSIGNED)";
            case POSTGRES -> "CAST(EXTRACT(EPOCH FROM clock_timestamp()) * 1000 AS BIGINT)";
            case SQLITE -> "(CAST(strftime('%s','now') AS INTEGER) * 1000)";
        };
    }

    private String kvHead() {
        return "INSERT " + (this == MYSQL ? "IGNORE " : "") + "INTO dc_kv (ns,k,v,deleted,updated) VALUES (?,?,?,0," + now() + ")";
    }

    public String kvUpsert() {
        String head = "INSERT INTO dc_kv (ns,k,v,deleted,updated) VALUES (?,?,?,0," + now() + ")";
        return this == MYSQL ? head + " ON DUPLICATE KEY UPDATE v=VALUES(v), deleted=0, updated=" + now()
                : head + " ON CONFLICT (ns,k) DO UPDATE SET v=excluded.v, deleted=0, updated=" + now();
    }

    public String kvInsertIgnore() {
        return this == MYSQL ? kvHead() : kvHead() + " ON CONFLICT DO NOTHING";
    }

    public String balanceInsertIgnore() {
        String s = "INSERT " + (this == MYSQL ? "IGNORE " : "") + "INTO dc_balance (uuid,balance,updated) VALUES (?,?," + now() + ")";
        return this == MYSQL ? s : s + " ON CONFLICT DO NOTHING";
    }

    public String balanceSet() {
        String head = "INSERT INTO dc_balance (uuid,balance,updated) VALUES (?,?," + now() + ")";
        return this == MYSQL ? head + " ON DUPLICATE KEY UPDATE balance=VALUES(balance), updated=" + now()
                : head + " ON CONFLICT (uuid) DO UPDATE SET balance=excluded.balance, updated=" + now();
    }

    /** Parameters: uuid, start balance (if new), delta. Never goes below 0. */
    public String balanceDelta() {
        String head = "INSERT INTO dc_balance (uuid,balance,updated) VALUES (?,?," + now() + ")";
        return switch (this) {
            case MYSQL -> head + " ON DUPLICATE KEY UPDATE balance = GREATEST(0, balance + ?), updated=" + now();
            case POSTGRES -> head + " ON CONFLICT (uuid) DO UPDATE SET balance = GREATEST(0, dc_balance.balance + ?), updated=" + now();
            case SQLITE -> head + " ON CONFLICT (uuid) DO UPDATE SET balance = MAX(0, dc_balance.balance + ?), updated=" + now();
        };
    }

    public List<String> schema() {
        String text = this == MYSQL ? "MEDIUMTEXT" : "TEXT";
        String flag = this == MYSQL ? "TINYINT(1)" : "SMALLINT";
        String dbl = this == MYSQL ? "DOUBLE" : "DOUBLE PRECISION";
        String tail = this == MYSQL ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" : "";
        String key = this == MYSQL ? ", KEY idx_updated (updated)" : "";
        List<String> l = new ArrayList<>();
        l.add("CREATE TABLE IF NOT EXISTS dc_kv (ns VARCHAR(24) NOT NULL, k VARCHAR(96) NOT NULL, v " + text
                + " NULL, deleted " + flag + " NOT NULL DEFAULT 0, updated BIGINT NOT NULL, PRIMARY KEY (ns, k)" + key + ")" + tail);
        l.add("CREATE TABLE IF NOT EXISTS dc_balance (uuid CHAR(36) NOT NULL PRIMARY KEY, balance " + dbl
                + " NOT NULL, updated BIGINT NOT NULL)" + tail);
        l.add("CREATE TABLE IF NOT EXISTS dc_counter (name VARCHAR(8) NOT NULL PRIMARY KEY, n BIGINT NOT NULL)" + tail);
        if (this != MYSQL) l.add("CREATE INDEX IF NOT EXISTS idx_dc_kv_updated ON dc_kv (updated)");
        return l;
    }
}
