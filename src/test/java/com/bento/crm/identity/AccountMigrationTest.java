package com.bento.crm.identity;

import com.bento.crm.support.IntegrationTestBase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V44 turns every address into one Account, however many Organizations it appears in. Runs the
 * real migrations on a database of its own: up to V43, then the data V44 has to handle, then V44.
 */
class AccountMigrationTest {

    private static final UUID ORG_A = UUID.randomUUID();
    private static final UUID ORG_B = UUID.randomUUID();
    private static final UUID ORG_C = UUID.randomUUID();

    private static JdbcTemplate jdbc;
    private static Flyway flyway;

    @BeforeAll
    static void migrateUpToTheAccountTable() {
        String database = "account_migration_" + UUID.randomUUID().toString().replace("-", "");
        var postgres = IntegrationTestBase.postgres;
        new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()))
                .execute("CREATE DATABASE " + database);
        String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + database);
        var dataSource = new DriverManagerDataSource(url, postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("43").load();
        flyway.migrate();

        for (UUID org : List.of(ORG_A, ORG_B, ORG_C)) {
            jdbc.update("INSERT INTO organization (id, name) VALUES (?, ?)", org, "Org " + org);
        }
        // One person in three Organizations, typed differently each time, with a different
        // password in each. The Membership used most recently is the one in B.
        membership(ORG_A, " Shared@Example.com", "hash-older", "2026-01-01T00:00:00Z", "2025-12-01T00:00:00Z");
        membership(ORG_B, "shared@example.com", "hash-most-recent", "2026-06-01T00:00:00Z", "2026-02-01T00:00:00Z");
        membership(ORG_C, "SHARED@example.com", "hash-never-used", null, "2026-03-01T00:00:00Z");
        membership(ORG_A, "solo@example.com", "hash-solo", null, "2026-04-01T00:00:00Z");

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("44").load().migrate();
    }

    @Test
    void anAddressSharedAcrossOrganizationsBecomesOneAccount() {
        List<UUID> accounts = jdbc.queryForList(
                "SELECT DISTINCT account_id FROM app_user WHERE lower(btrim(email)) = 'shared@example.com'", UUID.class);
        assertThat(accounts).hasSize(1);

        var account = jdbc.queryForMap("SELECT * FROM account WHERE id = ?", accounts.get(0));
        assertThat(account.get("email")).isEqualTo("shared@example.com");
        assertThat(account.get("password_hash")).isEqualTo("hash-most-recent");
        assertThat(((Timestamp) account.get("created_at")).toInstant()).isEqualTo(Instant.parse("2025-12-01T00:00:00Z"));
        assertThat(account.get("email_verified_at")).isNull();
    }

    @Test
    void everyMembershipBelongsToAnAccountWithItsOwnAddress() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE account_id IS NULL", Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM app_user u JOIN account a ON a.id = u.account_id
                WHERE a.email <> lower(btrim(u.email))""", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account", Long.class))
                .isEqualTo(jdbc.queryForObject("SELECT count(DISTINCT lower(btrim(email))) FROM app_user", Long.class));
        assertThat(jdbc.queryForObject(
                "SELECT password_hash FROM account WHERE email = 'solo@example.com'", String.class)).isEqualTo("hash-solo");
    }

    @Test
    void anAccountHoldsAtMostOneMembershipPerOrganization() {
        UUID account = jdbc.queryForObject("SELECT id FROM account WHERE email = 'solo@example.com'", UUID.class);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO app_user (organization_id, account_id, email, display_name) VALUES (?, ?, 'solo@example.com', 'Again')",
                ORG_A, account))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void accountAddressesAreStoredInLowerCase() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO account (email, password_hash) VALUES ('Mixed@Example.com', 'x')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static void membership(UUID org, String email, String passwordHash, String lastActiveAt, String createdAt) {
        jdbc.update("""
                        INSERT INTO app_user (organization_id, email, password_hash, display_name, last_active_at, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)""",
                org, email, passwordHash, "Person",
                lastActiveAt == null ? null : Timestamp.from(Instant.parse(lastActiveAt)),
                Timestamp.from(Instant.parse(createdAt)));
    }
}
