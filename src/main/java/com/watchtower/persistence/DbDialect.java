package com.watchtower.persistence;

public enum DbDialect {
    SQLITE,
    POSTGRES;

    public static DbDialect fromJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null) return SQLITE;
        String lower = jdbcUrl.toLowerCase();
        if (lower.startsWith("jdbc:postgresql:")) return POSTGRES;
        return SQLITE;
    }

    public String schemaResource() {
        return this == POSTGRES ? "schema-postgres.sql" : "schema.sql";
    }
}
