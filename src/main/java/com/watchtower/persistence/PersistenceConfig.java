package com.watchtower.persistence;

import com.watchtower.config.MonitorProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StreamUtils;

@Slf4j
@Configuration
public class PersistenceConfig {

    @Bean
    public DbDialect dbDialect(MonitorProperties props) {
        String url = props.getPersistence().getJdbcUrl();
        DbDialect dialect = (url == null || url.isBlank())
                ? DbDialect.SQLITE
                : DbDialect.fromJdbcUrl(url);
        log.info("persistence dialect: {}", dialect);
        return dialect;
    }

    @Bean
    public DataSource dataSource(MonitorProperties props, DbDialect dialect) {
        MonitorProperties.Persistence p = props.getPersistence();
        HikariConfig cfg = new HikariConfig();

        if (dialect == DbDialect.POSTGRES) {
            cfg.setJdbcUrl(p.getJdbcUrl());
            if (!p.getUsername().isBlank()) cfg.setUsername(p.getUsername());
            if (!p.getPassword().isBlank()) cfg.setPassword(p.getPassword());
            cfg.setDriverClassName("org.postgresql.Driver");
            cfg.setMaximumPoolSize(Math.max(2, p.getPoolSize()));
            cfg.setMinimumIdle(1);
            cfg.setPoolName("watchtower-postgres");
            log.info("PostgreSQL persistence at {}", p.getJdbcUrl());
        } else {
            Path path = Paths.get(p.getDbPath()).toAbsolutePath();
            Path parent = path.getParent();
            if (parent != null) {
                try {
                    Files.createDirectories(parent);
                } catch (IOException e) {
                    throw new IllegalStateException("cannot create db directory: " + parent, e);
                }
            }
            cfg.setJdbcUrl("jdbc:sqlite:" + path);
            cfg.setDriverClassName("org.sqlite.JDBC");
            cfg.setMaximumPoolSize(4);
            cfg.setMinimumIdle(1);
            cfg.setPoolName("watchtower-sqlite");
            cfg.setConnectionInitSql("PRAGMA busy_timeout=5000");
            log.info("SQLite persistence at {}", path);
        }
        return new HikariDataSource(cfg);
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public SchemaInitializer schemaInitializer(JdbcTemplate jdbc, DbDialect dialect) {
        return new SchemaInitializer(jdbc, dialect);
    }

    static class SchemaInitializer {
        SchemaInitializer(JdbcTemplate jdbc, DbDialect dialect) {
            try {
                if (dialect == DbDialect.SQLITE) {
                    jdbc.execute("PRAGMA journal_mode=WAL");
                    jdbc.execute("PRAGMA synchronous=NORMAL");
                    jdbc.execute("PRAGMA foreign_keys=ON");
                }
                String sql = StreamUtils.copyToString(
                        new ClassPathResource(dialect.schemaResource()).getInputStream(),
                        StandardCharsets.UTF_8);
                for (String stmt : sql.split(";")) {
                    String trimmed = stmt.trim();
                    if (!trimmed.isEmpty()) jdbc.execute(trimmed);
                }
                if (dialect == DbDialect.SQLITE) {
                    migrateSqlite(jdbc);
                }
                log.info("{} schema initialized", dialect);
            } catch (IOException e) {
                throw new IllegalStateException("failed to load schema resource", e);
            }
        }

        private void migrateSqlite(JdbcTemplate jdbc) {
            addColumnIfMissingSqlite(jdbc, "alarm_event", "acknowledged", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissingSqlite(jdbc, "alarm_event", "acknowledged_at", "INTEGER");
            addColumnIfMissingSqlite(jdbc, "alarm_event", "acknowledged_by", "TEXT");
        }

        private void addColumnIfMissingSqlite(JdbcTemplate jdbc, String table, String column, String type) {
            try {
                Integer count = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM pragma_table_info(?) WHERE name = ?",
                        Integer.class, table, column);
                if (count == null || count == 0) {
                    jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
                    log.info("migrated: added {}.{}", table, column);
                }
            } catch (Exception e) {
                log.warn("migration check failed for {}.{}: {}", table, column, e.getMessage());
            }
        }
    }
}
