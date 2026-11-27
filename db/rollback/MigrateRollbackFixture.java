import org.flywaydb.core.Flyway;

import java.nio.file.Path;
import java.util.Map;

/** Applies a single version to a disposable database of the rollback rehearsal. */
public final class MigrateRollbackFixture {
    public static void main(String[] args) {
        var database = args[0];
        int port = Integer.parseInt(args[1]);
        String version = args[2];
        if (!database.matches("cabinet_rollback_[0-9a-f]{20}") || port < 1 || port > 65535
                || !version.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("Expected an isolated database and a version");
        }
        var migration = Flyway.configure()
                .dataSource("jdbc:postgresql://127.0.0.1:" + port + "/" + database,
                        args[3], System.getenv("PGPASSWORD"))
                .locations("filesystem:" + Path.of(args[4]).toAbsolutePath())
                .target(version)
                .placeholders(Map.of("demo-accounts", "true"))
                .cleanDisabled(true)
                .load()
                .migrate();
        if (migration.migrationsExecuted != 1) {
            throw new IllegalStateException("Expected exactly one migration at version " + version);
        }
    }
}
