package io.matedata.identity;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.matedata.harness.application.AuditService;
import io.matedata.identity.application.IdentityService;
import io.matedata.identity.interfaces.AuthController;
import io.matedata.shared.ApplicationException;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class LoginAdmissionTest {
  @Test
  void oneAddressCannotResetTheBudgetByRotatingUsernames() {
    var service = mock(IdentityService.class);
    var audit = mock(AuditService.class);
    when(service.login(anyString(), anyString()))
        .thenThrow(
            new ApplicationException(ApplicationException.Kind.INVALID_CREDENTIALS, "invalid"));
    var controller = new AuthController(service, audit);
    var request = new MockHttpServletRequest();
    request.setRemoteAddr("127.0.0.9");
    for (int i = 0; i < 10; i++) {
      final int n = i;
      assertThatThrownBy(
              () ->
                  controller.login(new AuthController.Login("user_" + n, "some-password"), request))
          .isInstanceOfSatisfying(
              ApplicationException.class,
              e -> assertThat(e.kind()).isEqualTo(ApplicationException.Kind.INVALID_CREDENTIALS));
    }
    assertThatThrownBy(
            () ->
                controller.login(
                    new AuthController.Login("another_user", "some-password"), request))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            e -> assertThat(e.kind()).isEqualTo(ApplicationException.Kind.BUSY));
    verify(service, times(10)).login(anyString(), anyString());
    verify(audit, times(10)).record(anyString(), eq("AUTH_LOGIN_FAILED"), anyString());
  }

  @Test
  void unknownAccountsStillPerformOnePasswordHashComparison() {
    var accounts = mock(AccountRepository.class);
    var hash = mock(PasswordHasher.class);
    when(accounts.find("admin"))
        .thenReturn(Optional.of(new Account("admin", "Admin", "ADMIN", "admin-hash")));
    when(hash.encode(anyString())).thenReturn("dummy-hash");
    var service = new IdentityService(accounts, hash, "");
    assertThatThrownBy(() -> service.login("unknown", "some-password"))
        .isInstanceOf(ApplicationException.class);
    verify(hash).matches("some-password", "dummy-hash");
  }
}
