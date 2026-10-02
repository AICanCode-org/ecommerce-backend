package org.aicancode.shopflow.db;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Converts a libpq-style URL (postgresql://user:pass@host:5432/db) — the format every track
 * shares — into JDBC settings.
 */
public record DatabaseUrl(String jdbcUrl, String username, String password) {

    public static DatabaseUrl parse(String url) {
        URI uri = URI.create(url.replaceFirst("^postgres(ql)?://", "postgresql://"));
        String user = "";
        String pass = "";
        if (uri.getRawUserInfo() != null) {
            String[] parts = uri.getRawUserInfo().split(":", 2);
            user = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            pass = parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
        }
        int port = uri.getPort() == -1 ? 5432 : uri.getPort();
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
        return new DatabaseUrl("jdbc:postgresql://" + uri.getHost() + ":" + port + uri.getRawPath() + query, user, pass);
    }
}
