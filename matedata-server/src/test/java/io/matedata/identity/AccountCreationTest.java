package io.matedata.identity;

import static org.assertj.core.api.Assertions.*;

import io.matedata.identity.application.IdentityService;
import io.matedata.identity.infrastructure.BCryptPasswordHasher;
import io.matedata.identity.infrastructure.JdbcAccountRepository;
import io.matedata.shared.ApplicationException;
import io.matedata.shared.infrastructure.DocumentStore;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AccountCreationTest {
  private final BCryptPasswordHasher hasher = new BCryptPasswordHasher();

  private JdbcAccountRepository repository() {
    var store =
        new DocumentStore(
            new JdbcTemplate(
                new DriverManagerDataSource(
                    "jdbc:h2:mem:account_creation_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                    "sa",
                    "")));
    store.save(
        "identity",
        "admin",
        new Account("admin", "Admin", "ADMIN", hasher.encode("admin-password-2026")));
    return new JdbcAccountRepository(store);
  }

  @Test
  void duplicateCreationPreservesExistingPasswordNameAndRole() {
    var repository = repository();
    var identity = new IdentityService(repository, hasher, "");
    identity.create("alice", "Original", "VIEWER", "original-password-2026");
    var original = repository.find("alice").orElseThrow();

    assertThatThrownBy(
            () -> identity.create("alice", "Replacement", "ADMIN", "replacement-password-2026"))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            e -> assertThat(e.kind()).isEqualTo(ApplicationException.Kind.CONFLICT));

    assertThat(repository.find("alice")).contains(original);
    assertThat(identity.login("alice", "original-password-2026").role()).isEqualTo("VIEWER");
  }

  @Test
  void concurrentCreationHasOneWinnerAndNeverOverwritesItsCredentialsOrRole() throws Exception {
    var repository = repository();
    var barrier = new CyclicBarrier(2);
    PasswordHasher synchronizedHasher =
        new PasswordHasher() {
          public String encode(String password) {
            String hash = hasher.encode(password);
            if (password.startsWith("candidate-password-")) {
              try {
                barrier.await(10, TimeUnit.SECONDS);
              } catch (Exception e) {
                throw new AssertionError("Both create calls must reach password hashing", e);
              }
            }
            return hash;
          }

          public boolean matches(String password, String hash) {
            return hasher.matches(password, hash);
          }
        };
    var firstService = new IdentityService(repository, synchronizedHasher, "");
    var secondService = new IdentityService(repository, synchronizedHasher, "");
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first =
          executor.submit(
              () -> create(firstService, "First", "VIEWER", "candidate-password-first"));
      var second =
          executor.submit(
              () -> create(secondService, "Second", "ADMIN", "candidate-password-second"));
      var results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
      assertThat(results.stream().filter(IdentityService.User.class::isInstance)).hasSize(1);
      assertThat(results.stream().filter(ApplicationException.class::isInstance)).hasSize(1);
      var failure =
          (ApplicationException)
              results.stream()
                  .filter(ApplicationException.class::isInstance)
                  .findFirst()
                  .orElseThrow();
      assertThat(failure.kind()).isEqualTo(ApplicationException.Kind.CONFLICT);
      var winner =
          (IdentityService.User)
              results.stream()
                  .filter(IdentityService.User.class::isInstance)
                  .findFirst()
                  .orElseThrow();
      var saved = repository.find("alice").orElseThrow();
      assertThat(saved.displayName()).isEqualTo(winner.displayName());
      assertThat(saved.role()).isEqualTo(winner.role());
      String winningPassword =
          winner.displayName().equals("First")
              ? "candidate-password-first"
              : "candidate-password-second";
      String losingPassword =
          winner.displayName().equals("First")
              ? "candidate-password-second"
              : "candidate-password-first";
      assertThat(firstService.login("alice", winningPassword)).isEqualTo(winner);
      assertThatThrownBy(() -> firstService.login("alice", losingPassword))
          .isInstanceOfSatisfying(
              ApplicationException.class,
              e -> assertThat(e.kind()).isEqualTo(ApplicationException.Kind.INVALID_CREDENTIALS));
    }
  }

  private Object create(IdentityService identity, String name, String role, String password) {
    try {
      return identity.create("alice", name, role, password);
    } catch (ApplicationException e) {
      return e;
    }
  }
}
