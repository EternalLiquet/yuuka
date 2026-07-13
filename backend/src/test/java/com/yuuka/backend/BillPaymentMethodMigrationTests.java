package com.yuuka.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yuuka.backend.support.AbstractIntegrationTest;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class BillPaymentMethodMigrationTests extends AbstractIntegrationTest {
  @Autowired private DataSource dataSource;

  @Test
  void v10BackfillsAndEnforcesBillPaymentMethodCombinations() {
    String schema = "payment_method_migration_" + UUID.randomUUID().toString().replace("-", "");
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);

    migrate(schema, MigrationVersion.fromVersion("9"));
    SeedIds ids = seedPreV10Rows(jdbc, schema);

    migrate(schema, null);

    assertThat(
            jdbc.queryForObject(
                "select payment_method from " + schema + ".paycheck_entries where id = ?",
                String.class,
                ids.paycheckBillId()))
        .isEqualTo("AUTOPAY");
    assertThat(
            jdbc.queryForObject(
                "select payment_method from " + schema + ".paycheck_entries where id = ?",
                String.class,
                ids.paycheckBucketId()))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "select payment_method from " + schema + ".template_entries where id = ?",
                String.class,
                ids.templateBillId()))
        .isEqualTo("AUTOPAY");
    assertThat(
            jdbc.queryForObject(
                "select payment_method from " + schema + ".template_entries where id = ?",
                String.class,
                ids.templateBucketId()))
        .isNull();

    assertThatThrownBy(
            () ->
                insertPaycheckEntry(
                    jdbc, schema, ids.ownerId(), ids.paycheckId(), EntrySeed.bill(null, 2)))
        .isInstanceOf(Exception.class);
    assertThatThrownBy(
            () ->
                insertPaycheckEntry(
                    jdbc, schema, ids.ownerId(), ids.paycheckId(), EntrySeed.bucket("AUTOPAY", 3)))
        .isInstanceOf(Exception.class);
    assertThatThrownBy(
            () ->
                insertPaycheckEntry(
                    jdbc, schema, ids.ownerId(), ids.paycheckId(), EntrySeed.bucket("MANUAL", 4)))
        .isInstanceOf(Exception.class);
    assertThatThrownBy(
            () ->
                insertPaycheckEntry(
                    jdbc, schema, ids.ownerId(), ids.paycheckId(), EntrySeed.bill("CARD", 5)))
        .isInstanceOf(Exception.class);
    assertThatThrownBy(
            () ->
                insertTemplateEntry(
                    jdbc, schema, ids.ownerId(), ids.templateId(), EntrySeed.bill(null, 2)))
        .isInstanceOf(Exception.class);
    assertThatThrownBy(
            () ->
                insertTemplateEntry(
                    jdbc, schema, ids.ownerId(), ids.templateId(), EntrySeed.bucket("AUTOPAY", 3)))
        .isInstanceOf(Exception.class);
    assertThatThrownBy(
            () ->
                insertTemplateEntry(
                    jdbc, schema, ids.ownerId(), ids.templateId(), EntrySeed.bucket("MANUAL", 4)))
        .isInstanceOf(Exception.class);
    assertThatThrownBy(
            () ->
                insertTemplateEntry(
                    jdbc, schema, ids.ownerId(), ids.templateId(), EntrySeed.bill("CARD", 5)))
        .isInstanceOf(Exception.class);

    insertPaycheckEntry(
        jdbc, schema, ids.ownerId(), ids.paycheckId(), EntrySeed.bill("AUTOPAY", 6));
    insertPaycheckEntry(jdbc, schema, ids.ownerId(), ids.paycheckId(), EntrySeed.bill("MANUAL", 7));
    insertPaycheckEntry(jdbc, schema, ids.ownerId(), ids.paycheckId(), EntrySeed.bucket(null, 8));
    insertTemplateEntry(
        jdbc, schema, ids.ownerId(), ids.templateId(), EntrySeed.bill("AUTOPAY", 6));
    insertTemplateEntry(jdbc, schema, ids.ownerId(), ids.templateId(), EntrySeed.bill("MANUAL", 7));
    insertTemplateEntry(jdbc, schema, ids.ownerId(), ids.templateId(), EntrySeed.bucket(null, 8));

    assertThat(
            jdbc.queryForObject(
                "select count(*) from " + schema + ".paycheck_entries", Integer.class))
        .isEqualTo(5);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from " + schema + ".template_entries", Integer.class))
        .isEqualTo(5);
  }

  private void migrate(String schema, MigrationVersion target) {
    var configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .defaultSchema(schema)
            .schemas(schema)
            .locations("classpath:db/migration");
    if (target != null) {
      configuration.target(target);
    }
    configuration.load().migrate();
  }

  private SeedIds seedPreV10Rows(JdbcTemplate jdbc, String schema) {
    UUID ownerId = UUID.randomUUID();
    UUID templateId = UUID.randomUUID();
    UUID paycheckId = UUID.randomUUID();
    UUID templateBillId = UUID.randomUUID();
    UUID templateBucketId = UUID.randomUUID();
    UUID paycheckBillId = UUID.randomUUID();
    UUID paycheckBucketId = UUID.randomUUID();

    jdbc.update(
        "insert into "
            + schema
            + ".user_accounts (id, email, password_hash, role, created_at, updated_at) "
            + "values (?, ?, ?, ?, now(), now())",
        ownerId,
        "migration-" + ownerId + "@yuuka.local",
        "hash",
        "OWNER");
    jdbc.update(
        "insert into "
            + schema
            + ".templates "
            + "(id, owner_id, name, archived, created_at, updated_at) "
            + "values (?, ?, ?, false, now(), now())",
        templateId,
        ownerId,
        "Template");
    jdbc.update(
        "insert into "
            + schema
            + ".template_entries "
            + "(id, owner_id, template_id, entry_type, name, default_amount_minor, position, "
            + "created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, now(), now())",
        templateBillId,
        ownerId,
        templateId,
        "BILL",
        "Template Bill",
        1000,
        0);
    jdbc.update(
        "insert into "
            + schema
            + ".template_entries "
            + "(id, owner_id, template_id, entry_type, name, default_amount_minor, position, "
            + "created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, now(), now())",
        templateBucketId,
        ownerId,
        templateId,
        "SPENDING_BUCKET",
        "Template Bucket",
        1000,
        1);
    jdbc.update(
        "insert into "
            + schema
            + ".paychecks "
            + "(id, owner_id, name, amount_minor, income_date, state, created_at, updated_at) "
            + "values (?, ?, ?, ?, current_date, ?, now(), now())",
        paycheckId,
        ownerId,
        "Paycheck",
        5000,
        "ACTIVE");
    insertPreV10PaycheckEntry(
        jdbc, schema, ownerId, paycheckId, paycheckBillId, "BILL", "Paycheck Bill", 0);
    insertPreV10PaycheckEntry(
        jdbc,
        schema,
        ownerId,
        paycheckId,
        paycheckBucketId,
        "SPENDING_BUCKET",
        "Paycheck Bucket",
        1);

    return new SeedIds(
        ownerId,
        templateId,
        paycheckId,
        templateBillId,
        templateBucketId,
        paycheckBillId,
        paycheckBucketId);
  }

  private void insertPreV10PaycheckEntry(
      JdbcTemplate jdbc,
      String schema,
      UUID ownerId,
      UUID paycheckId,
      UUID entryId,
      String entryType,
      String name,
      int position) {
    jdbc.update(
        "insert into "
            + schema
            + ".paycheck_entries "
            + "(id, owner_id, paycheck_id, entry_type, name, amount_minor, status, position, "
            + "created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
        entryId,
        ownerId,
        paycheckId,
        entryType,
        name,
        1000,
        "NOT_PAID",
        position);
  }

  private void insertPaycheckEntry(
      JdbcTemplate jdbc, String schema, UUID ownerId, UUID paycheckId, EntrySeed entry) {
    jdbc.update(
        "insert into "
            + schema
            + ".paycheck_entries "
            + "(id, owner_id, paycheck_id, entry_type, payment_method, name, amount_minor, status, "
            + "position, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
        UUID.randomUUID(),
        ownerId,
        paycheckId,
        entry.entryType(),
        entry.paymentMethod(),
        "Constraint " + entry.position(),
        1000,
        "NOT_PAID",
        entry.position());
  }

  private void insertTemplateEntry(
      JdbcTemplate jdbc, String schema, UUID ownerId, UUID templateId, EntrySeed entry) {
    jdbc.update(
        "insert into "
            + schema
            + ".template_entries "
            + "(id, owner_id, template_id, entry_type, payment_method, name, "
            + "default_amount_minor, position, created_at, updated_at) "
            + "values (?, ?, ?, ?, ?, ?, ?, ?, now(), now())",
        UUID.randomUUID(),
        ownerId,
        templateId,
        entry.entryType(),
        entry.paymentMethod(),
        "Constraint " + entry.position(),
        1000,
        entry.position());
  }

  private record EntrySeed(String entryType, String paymentMethod, int position) {
    static EntrySeed bill(String paymentMethod, int position) {
      return new EntrySeed("BILL", paymentMethod, position);
    }

    static EntrySeed bucket(String paymentMethod, int position) {
      return new EntrySeed("SPENDING_BUCKET", paymentMethod, position);
    }
  }

  private record SeedIds(
      UUID ownerId,
      UUID templateId,
      UUID paycheckId,
      UUID templateBillId,
      UUID templateBucketId,
      UUID paycheckBillId,
      UUID paycheckBucketId) {}
}
