package com.example.abidiff.store;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.StreamUtils;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Two manually-managed SQLite databases:
 * <ul>
 *   <li>{@code snapshotDataSource} - extracted snapshots and derived comparisons;</li>
 *   <li>{@code decisionDataSource} - reviewer resolutions, in a separate file.</li>
 * </ul>
 * IMMEDIATE transactions take the write lock up-front, so concurrent decision
 * submissions serialize cleanly (SQLITE_BUSY retry) instead of hitting a
 * write-lock upgrade deadlock.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    public DataSource snapshotDataSource(@Value("${abidiff.snapshot-db}") String dbPath) {
        return build(dbPath, "db/snapshot-schema.sql");
    }

    @Bean
    public DataSource decisionDataSource(@Value("${abidiff.decision-db}") String dbPath) {
        return build(dbPath, "db/decision-schema.sql");
    }

    @Bean
    public JdbcTemplate snapshotJdbc(DataSource snapshotDataSource) {
        return new JdbcTemplate(snapshotDataSource);
    }

    @Bean
    public JdbcTemplate decisionJdbc(DataSource decisionDataSource) {
        return new JdbcTemplate(decisionDataSource);
    }

    private DataSource build(String dbPath, String schemaResource) {
        try {
            Path p = Path.of(dbPath);
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            String url = "jdbc:sqlite:" + p.toAbsolutePath()
                    + "?transaction_mode=IMMEDIATE&busy_timeout=10000&foreign_keys=on";
            DriverManagerDataSource ds = new DriverManagerDataSource(url);
            initSchema(ds, schemaResource);
            return ds;
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("cannot initialise database " + dbPath, e);
        }
    }

    private void initSchema(DataSource ds, String schemaResource) throws IOException, SQLException {
        String sql = StreamUtils.copyToString(new ClassPathResource(schemaResource).getInputStream(),
                StandardCharsets.UTF_8);
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            for (String part : sql.split(";")) {
                if (!part.isBlank()) {
                    st.execute(part);
                }
            }
        }
    }
}
