package com.yuuka.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.yuuka.backend.support.AbstractIntegrationTest;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class RecurringBillAmountModeMigrationTests extends AbstractIntegrationTest {
  @Autowired private DataSource dataSource;

  @Test
  void v14MigratesExistingDefinitionsToFixedWithoutMaterializingOccurrences() {
    String schema = "recurring_amount_migration_" + UUID.randomUUID().toString().replace("-", "");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    migrate(schema, MigrationVersion.fromVersion("13"));
    UUID ownerId = UUID.randomUUID();
    UUID definitionId = UUID.randomUUID();
    jdbc.update(
        "insert into "
            + schema
            + ".user_accounts (id, email, password_hash, role, created_at, updated_at) "
            + "values (?, ?, 'hash', 'OWNER', now(), now())",
        ownerId,
        "migration-" + ownerId + "@yuuka.local");
    jdbc.update(
        "insert into "
            + schema
            + ".recurring_bill_definitions "
            + "(id, owner_id, name, typical_amount_minor, payment_method, recurrence_type, "
            + "due_day, active, version, created_at, updated_at) "
            + "values (?, ?, 'Electric', 14237, 'AUTOPAY', 'MONTHLY', 18, true, 0, now(), now())",
        definitionId,
        ownerId);

    migrate(schema, null);

    assertThat(
            jdbc.queryForObject(
                "select amount_mode from " + schema + ".recurring_bill_definitions where id = ?",
                String.class,
                definitionId))
        .isEqualTo("FIXED");
    assertThat(
            jdbc.queryForObject(
                "select typical_amount_minor from "
                    + schema
                    + ".recurring_bill_definitions where id = ?",
                Long.class,
                definitionId))
        .isEqualTo(14237L);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from " + schema + ".recurring_bill_occurrence_amounts",
                Integer.class))
        .isZero();
  }

  private void migrate(String schema, MigrationVersion target) {
    var configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .defaultSchema(schema)
            .schemas(schema)
            .locations("classpath:db/migration");
    if (target != null) configuration.target(target);
    configuration.load().migrate();
  }
}
