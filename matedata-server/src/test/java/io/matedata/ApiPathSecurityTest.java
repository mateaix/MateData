package io.matedata;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:pathsecurity;DB_CLOSE_DELAY=-1",
      "matedata.admin-password=path-test-password-2026",
      "matedata.data-dir=target/path-test-data"
    })
class ApiPathSecurityTest {
  @LocalServerPort int port;

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api;x/v1/system",
        "/%61pi/v1/system",
        "/api//v1/system",
        "/api/./v1/system",
        "/api/x/../v1/system",
        "/api/v1;x/system"
      })
  void normalizedApiRoutesStillRequireAuthentication(String path) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var response =
          client.send(
              HttpRequest.newBuilder(uri(path)).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).as(path).isEqualTo(401);
      assertThat(response.body()).contains("UNAUTHENTICATED");
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"/api;x/v1/auth/login", "/%61pi/v1/auth/login", "/api/v1/auth/login;x"})
  void normalizedLoginCannotBypassCsrfOrBodyBudget(String path) throws Exception {
    try (var client = HttpClient.newHttpClient()) {
      var missingHeader =
          HttpRequest.newBuilder(uri(path))
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString("{}"));
      assertThat(
              client.send(missingHeader.build(), HttpResponse.BodyHandlers.ofString()).statusCode())
          .as(path)
          .isEqualTo(403);
      var oversized =
          HttpRequest.newBuilder(uri(path))
              .header("Content-Type", "application/json")
              .header("X-MateData-Request", "1")
              .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[256 * 1024 + 1]));
      assertThat(client.send(oversized.build(), HttpResponse.BodyHandlers.ofString()).statusCode())
          .as(path)
          .isEqualTo(413);
    }
  }

  private URI uri(String path) {
    return URI.create("http://127.0.0.1:" + port + path);
  }
}
