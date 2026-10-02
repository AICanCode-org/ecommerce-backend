package org.aicancode.shopflow.db;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * A tiny, transparent SQL migrator. Why not Flyway? The three tracks share the SAME .sql files in
 * spec/db/migrations, so a small migrator keeps them in lock-step. Applied versions are recorded in
 * schema_migrations and a Postgres advisory lock stops two instances from racing.
 */
public class Migrator {

    static final long LOCK_ID = 7372026L;
    private static final Pattern FILE = Pattern.compile("^V(\\d+)__.+\\.sql$");
    private static final Logger LOG = LoggerFactory.getLogger(Migrator.class);

    private final DataSource dataSource;
    private final Path directory;

    public Migrator(DataSource dataSource, Path directory) {
        this.dataSource = dataSource;
        this.directory = directory;
    }

    public List<Integer> migrate() {
        List<Integer> applied = new ArrayList<>();
        try (Connection connection = dataSource.getConnection()) {
            // Use one connection for lock + DDL so the advisory lock really protects the work.
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.queryForList("SELECT pg_advisory_lock(?)", LOCK_ID);
            try {
                jdbc.execute("CREATE TABLE IF NOT EXISTS schema_migrations ("
                        + " version INTEGER PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL DEFAULT now())");
                Set<Integer> done = new HashSet<>(jdbc.queryForList("SELECT version FROM schema_migrations", Integer.class));
                for (Migration m : pending()) {
                    if (done.contains(m.version())) {
                        continue;
                    }
                    LOG.info("applying migration {}", m.file().getFileName());
                    connection.setAutoCommit(false);
                    try {
                        jdbc.execute(Files.readString(m.file()));
                        jdbc.update("INSERT INTO schema_migrations (version) VALUES (?)", m.version());
                        connection.commit();
                    } catch (RuntimeException | IOException e) {
                        connection.rollback();
                        throw e;
                    } finally {
                        connection.setAutoCommit(true);
                    }
                    applied.add(m.version());
                }
            } finally {
                jdbc.queryForList("SELECT pg_advisory_unlock(?)", LOCK_ID);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("migration failed", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        LOG.info("migrations applied this start: {}", applied.isEmpty() ? "none" : applied);
        return applied;
    }

    List<Migration> pending() throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(path -> {
                        Matcher m = FILE.matcher(path.getFileName().toString());
                        return m.matches() ? new Migration(Integer.parseInt(m.group(1)), path) : null;
                    })
                    .filter(java.util.Objects::nonNull)
                    .sorted(java.util.Comparator.comparingInt(Migration::version))
                    .toList();
        }
    }

    record Migration(int version, Path file) {
    }
}
