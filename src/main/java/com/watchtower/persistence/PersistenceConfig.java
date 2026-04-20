package com.watchtower.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StreamUtils;

@Slf4j
@Configuration
public class PersistenceConfig {

    @Bean
    public DataSource dataSource(@Value("${watchtower.persistence.db-path:./data/watchtower.db}") String dbPath) {
        Path path = Paths.get(dbPath).toAbsolutePath();
        Path parent = path.getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (IOException e) {
                throw new IllegalStateException("cannot create db directory: " + parent, e);
            }
        }
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:sqlite:" + path);
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setMaximumPoolSize(4);
        cfg.setMinimumIdle(1);
        cfg.setPoolName("watchtower-sqlite");
        cfg.setConnectionInitSql("PRAGMA busy_timeout=5000");
        log.info("SQLite persistence at {}", path);
        return new HikariDataSource(cfg);
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public SchemaInitializer schemaInitializer(JdbcTemplate jdbc) {
        return new SchemaInitializer(jdbc);
    }

    static class SchemaInitializer {
        SchemaInitializer(JdbcTemplate jdbc) {
            try {
                jdbc.execute("PRAGMA journal_mode=WAL");
                jdbc.execute("PRAGMA synchronous=NORMAL");
                jdbc.execute("PRAGMA foreign_keys=ON");
                String sql = StreamUtils.copyToString(
                        new ClassPathResource("schema.sql").getInputStream(), StandardCharsets.UTF_8);
                for (String stmt : sql.split(";")) {
                    String trimmed = stmt.trim();
                    if (!trimmed.isEmpty()) jdbc.execute(trimmed);
                }
                migrate(jdbc);
                log.info("SQLite schema initialized");
            } catch (IOException e) {
                throw new IllegalStateException("failed to load schema.sql", e);
            }
        }

        private void migrate(JdbcTemplate jdbc) {
            addColumnIfMissing(jdbc, "alarm_event", "acknowledged", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(jdbc, "alarm_event", "acknowledged_at", "INTEGER");
            addColumnIfMissing(jdbc, "alarm_event", "acknowledged_by", "TEXT");
        }

        private void addColumnIfMissing(JdbcTemplate jdbc, String table, String column, String type) {
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
