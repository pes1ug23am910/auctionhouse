package io.auctionhouse.ops;

import java.nio.file.Files;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;

/** One-shot migration command packaged with the exact application release. */
public final class Migrate {
    private Migrate() {}
    public static void main(String[] args) throws Exception {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        String password = System.getenv("AUCTIONHOUSE_DB_PASSWORD");
        String file = System.getenv("AUCTIONHOUSE_DB_PASSWORD_FILE");
        if (file != null) { password = Files.readString(Path.of(file)).stripTrailing(); }
        if (password == null || password.isEmpty()) { throw new IllegalStateException("Missing migration credential"); }
        Flyway.configure().dataSource(required("AUCTIONHOUSE_DB_URL"), required("AUCTIONHOUSE_DB_USER"), password)
                .locations("classpath:db/migration").validateMigrationNaming(true).cleanDisabled(true)
                .load().migrate();
        String runtimeRole = System.getenv("AUCTIONHOUSE_RUNTIME_DB_USER");
        if (runtimeRole != null) {
            if (!runtimeRole.equals("ah_runtime")) { throw new IllegalArgumentException("Unexpected runtime role"); }
            try (var connection = java.sql.DriverManager.getConnection(required("AUCTIONHOUSE_DB_URL"), required("AUCTIONHOUSE_DB_USER"), password);
                 var statement = connection.createStatement()) {
                statement.execute("REVOKE ALL ON TABLE public.flyway_schema_history FROM ah_runtime");
            }
        }
    }
    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) { throw new IllegalStateException("Missing " + name); }
        return value;
    }
}
