package io.matedata.identity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.matedata.identity.application.IdentityService;
import java.util.*;
import org.junit.jupiter.api.Test;

class IdentityValidationTest {
  IdentityService service() {
    var repository = mock(AccountRepository.class);
    when(repository.find("admin"))
        .thenReturn(Optional.of(new Account("admin", "Admin", "ADMIN", "hash")));
    return new IdentityService(repository, mock(PasswordHasher.class), "");
  }

  @Test
  void rejectsMissingRoleAsInvalidInput() {
    assertThatThrownBy(() -> service().create("alice", "Alice", null, "long-password-123"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsPasswordThatExceedsBcryptUtf8Limit() {
    assertThatThrownBy(() -> service().create("alice", "Alice", "ANALYST", "密".repeat(25)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void missingGrantCollectionsAreValidationErrors() {
    assertThatThrownBy(() -> new DatasetGrant("alice", "sales", true, null, null, null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
