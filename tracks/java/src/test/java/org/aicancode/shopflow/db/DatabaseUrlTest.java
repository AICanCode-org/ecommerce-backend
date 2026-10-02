package org.aicancode.shopflow.db;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DatabaseUrlTest {

    @Test
    void convertsLibpqUrlToJdbc() {
        DatabaseUrl url = DatabaseUrl.parse("postgresql://shopflow:s%40cret@db.example.com:6543/app?sslmode=require");
        assertThat(url.jdbcUrl()).isEqualTo("jdbc:postgresql://db.example.com:6543/app?sslmode=require");
        assertThat(url.username()).isEqualTo("shopflow");
        assertThat(url.password()).isEqualTo("s@cret");
    }

    @Test
    void defaultsPortAndAcceptsPostgresScheme() {
        assertThat(DatabaseUrl.parse("postgres://u:p@localhost/shopflow").jdbcUrl())
                .isEqualTo("jdbc:postgresql://localhost:5432/shopflow");
    }
}
