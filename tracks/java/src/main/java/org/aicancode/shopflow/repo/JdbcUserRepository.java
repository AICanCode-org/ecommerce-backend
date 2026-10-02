package org.aicancode.shopflow.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import org.aicancode.shopflow.domain.Models.User;
import org.springframework.jdbc.core.simple.JdbcClient;

/** PostgreSQL adapter for users (plain SQL with JdbcClient, no ORM). */
public class JdbcUserRepository implements UserRepository {

    private static final String COLS = "id, email, password_hash, display_name, role, created_at";
    private final JdbcClient jdbc;

    public JdbcUserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static User user(ResultSet rs, int row) throws SQLException {
        return new User(rs.getLong("id"), rs.getString("email"), rs.getString("password_hash"),
                rs.getString("display_name"), rs.getString("role"), Jdbc.instant(rs, "created_at"));
    }

    @Override
    public User insertUser(String email, String passwordHash, String displayName, String role, Instant at) {
        // TODO(phase-3 M2): INSERT ... RETURNING; a unique violation on email (SQLSTATE 23505) ->
        //   DuplicateError.
        throw new UnsupportedOperationException("TODO(phase-3 M2): JdbcUserRepository.insertUser");
    }

    @Override
    public Optional<User> findUserByEmail(String email) {
        // TODO(phase-3 M2): SELECT by email (already lower-cased).
        throw new UnsupportedOperationException("TODO(phase-3 M2): JdbcUserRepository.findUserByEmail");
    }

    @Override
    public Optional<User> findUser(long id) {
        // TODO(phase-3 M2): SELECT by id.
        throw new UnsupportedOperationException("TODO(phase-3 M2): JdbcUserRepository.findUser");
    }

    @Override
    public Optional<User> updateRole(long id, String role) {
        // TODO(phase-3 M2): UPDATE ... RETURNING; null when the user does not exist.
        throw new UnsupportedOperationException("TODO(phase-3 M2): JdbcUserRepository.updateRole");
    }

    @Override
    public boolean ping() {
        try {
            jdbc.sql("SELECT 1").query(Integer.class).single();
            return true;
        } catch (RuntimeException e) { // health checks must never throw
            return false;
        }
    }
}
