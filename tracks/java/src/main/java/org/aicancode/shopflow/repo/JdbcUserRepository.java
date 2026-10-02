package org.aicancode.shopflow.repo;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import org.aicancode.shopflow.domain.DuplicateException;
import org.aicancode.shopflow.domain.Models.User;
import org.springframework.dao.DuplicateKeyException;
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
        try {
            return jdbc.sql("INSERT INTO users (email, password_hash, display_name, role, created_at) "
                            + "VALUES (?, ?, ?, ?, ?) RETURNING " + COLS)
                    .params(email, passwordHash, displayName, role, Jdbc.ts(at))
                    .query(JdbcUserRepository::user).single();
        } catch (DuplicateKeyException e) {
            throw new DuplicateException("email");
        }
    }

    @Override
    public Optional<User> findUserByEmail(String email) {
        return jdbc.sql("SELECT " + COLS + " FROM users WHERE email = ?").param(email)
                .query(JdbcUserRepository::user).optional();
    }

    @Override
    public Optional<User> findUser(long id) {
        return jdbc.sql("SELECT " + COLS + " FROM users WHERE id = ?").param(id).query(JdbcUserRepository::user).optional();
    }

    @Override
    public Optional<User> updateRole(long id, String role) {
        return jdbc.sql("UPDATE users SET role = ? WHERE id = ? RETURNING " + COLS).params(role, id)
                .query(JdbcUserRepository::user).optional();
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
