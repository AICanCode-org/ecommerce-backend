package org.aicancode.shopflow.repo;

import java.time.Instant;
import java.util.Optional;
import org.aicancode.shopflow.domain.Models.User;

/** Persistence port for users. */
public interface UserRepository {

    User insertUser(String email, String passwordHash, String displayName, String role, Instant at);

    Optional<User> findUserByEmail(String email);

    Optional<User> findUser(long id);

    Optional<User> updateRole(long id, String role);

    boolean ping();
}
