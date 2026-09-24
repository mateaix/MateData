package io.matedata.shared.interfaces;

import static org.assertj.core.api.Assertions.assertThat;

import io.matedata.shared.ApplicationException;
import io.matedata.shared.ApplicationException.Kind;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApiErrorsTest {
  @Test
  void applicationFailuresKeepExistingHttpContract() {
    var expected =
        Map.of(
            Kind.UNAUTHENTICATED,
            401,
            Kind.INVALID_CREDENTIALS,
            401,
            Kind.FORBIDDEN,
            403,
            Kind.NOT_FOUND,
            404,
            Kind.CONFLICT,
            409,
            Kind.BUSY,
            429,
            Kind.INVALID_REQUEST,
            400,
            Kind.CONNECTION_FAILED,
            422);
    assertThat(expected).hasSize(Kind.values().length);
    expected.forEach(
        (kind, status) -> {
          var response = new ApiErrors().known(new ApplicationException(kind, "message"));
          assertThat(response.getStatusCode().value()).isEqualTo(status);
          var body = (Map<?, ?>) response.getBody();
          assertThat(body.get("code")).isEqualTo(kind.name());
          assertThat(body.get("message")).isEqualTo("message");
        });
  }
}
