package io.matedata;

import static org.assertj.core.api.Assertions.*;

import io.matedata.harness.application.ModelSettings;
import io.matedata.harness.infrastructure.JdbcModelConfigurationRepository;
import io.matedata.identity.application.IdentityService;
import io.matedata.identity.infrastructure.BCryptPasswordHasher;
import io.matedata.identity.infrastructure.JdbcAccountRepository;
import io.matedata.shared.ApplicationException;
import io.matedata.shared.infrastructure.DocumentStore;
import io.matedata.shared.infrastructure.SecretVault;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PortPersistenceTest {
  @TempDir Path directory;

  DocumentStore store() {
    return new DocumentStore(
        new JdbcTemplate(
            new DriverManagerDataSource(
                "jdbc:h2:mem:ports_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "")));
  }

  @Test
  void modelSettingsReadExistingDocumentsAndPreserveSecretOnBlankUpdate() throws Exception {
    var store = store();
    var vault = new SecretVault(directory.toString(), "");
    String encrypted = vault.encrypt("existing-api-key");
    store.save(
        "harness-settings",
        "model",
        Map.of(
            "baseUrl",
            "https://api.example.test/v1",
            "model",
            "existing-model",
            "encryptedKey",
            encrypted,
            "maxSteps",
            6,
            "timeoutSeconds",
            60));
    var settings = new ModelSettings(new JdbcModelConfigurationRepository(store), vault);
    assertThat(settings.configured()).isTrue();
    assertThat(settings.key(settings.current())).isEqualTo("existing-api-key");
    var view = settings.save("https://api.example.test/v1/", "new-model", "", 8, 90);
    assertThat(view).doesNotContainKeys("apiKey", "encryptedKey");
    var reloaded = new ModelSettings(new JdbcModelConfigurationRepository(store), vault);
    assertThat(reloaded.current().model()).isEqualTo("new-model");
    assertThat(reloaded.current().encryptedKey()).isEqualTo(encrypted);
    assertThat(reloaded.current().baseUrl()).isEqualTo("https://api.example.test/v1");
  }

  @Test
  void identityReadsExistingAccountDocumentsAndPersistsNewHashedPasswords() {
    var store = store();
    var hasher = new BCryptPasswordHasher();
    store.save(
        "identity",
        "admin",
        Map.of(
            "username",
            "admin",
            "displayName",
            "Existing admin",
            "role",
            "ADMIN",
            "passwordHash",
            hasher.encode("old-password-2026")));
    var identity =
        new IdentityService(new JdbcAccountRepository(store), hasher, "replacement-password");
    assertThat(identity.login("admin", "old-password-2026").displayName())
        .isEqualTo("Existing admin");
    identity.create("analyst", "Analyst", "ANALYST", "analyst-password-2026");
    var reloaded = new IdentityService(new JdbcAccountRepository(store), hasher, "");
    assertThat(reloaded.login("analyst", "analyst-password-2026").role()).isEqualTo("ANALYST");
    assertThatThrownBy(() -> reloaded.login("analyst", "wrong-password"))
        .isInstanceOf(ApplicationException.class);
    assertThat(new JdbcAccountRepository(store).find("analyst").orElseThrow().passwordHash())
        .startsWith("$2")
        .doesNotContain("analyst-password");
  }

  @Test
  void semanticServiceValidatesPhysicalColumnsBeforePersistingChanges() throws Exception {
    var store = store();
    var vault = new SecretVault(directory.toString(), "");
    var catalog =
        new io.matedata.catalog.application.CatalogService(
            new io.matedata.catalog.infrastructure.JdbcSourceRepository(store),
            vault,
            new io.matedata.catalog.infrastructure.BusinessConnections(vault));
    var repository = new io.matedata.semantic.infrastructure.JdbcModelRepository(store);
    var service = new io.matedata.semantic.application.SemanticService(repository, catalog);
    var sales = io.matedata.semantic.SemanticModel.sales();
    var valid =
        new io.matedata.semantic.SemanticModel(
            "custom_sales",
            "Custom sales",
            "",
            sales.sourceId(),
            sales.tableName(),
            sales.metrics(),
            sales.dimensions());
    var published = service.create(valid);
    assertThat(published.tableName()).isEqualTo("SALES");
    assertThat(published.dialect()).isEqualTo(io.matedata.semantic.SemanticModel.Dialect.ANSI);
    assertThat(repository.find(valid.id())).contains(published);
    assertThatThrownBy(() -> service.create(valid)).isInstanceOf(ApplicationException.class);
    var invalid =
        new io.matedata.semantic.SemanticModel(
            valid.id(),
            "Invalid column",
            "",
            sales.sourceId(),
            sales.tableName(),
            java.util.List.of(
                new io.matedata.semantic.SemanticModel.Metric(
                    "missing", "Missing", "missing_column", "SUM", java.util.List.of())),
            sales.dimensions());
    assertThatThrownBy(() -> service.update(valid.id(), invalid))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("物理");
    assertThat(repository.find(valid.id())).contains(published);
    assertThatThrownBy(() -> service.update("other_id", valid))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
