package io.matedata.identity;

import static org.assertj.core.api.Assertions.*;

import io.matedata.identity.interfaces.SessionGuard;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class ApiPayloadLimitTest {
  MockHttpServletRequest request(boolean unknownLength, byte[] body) {
    var request =
        new MockHttpServletRequest("POST", "/api/v1/auth/login") {
          @Override
          public long getContentLengthLong() {
            return unknownLength ? -1 : super.getContentLengthLong();
          }

          @Override
          public int getContentLength() {
            return unknownLength ? -1 : super.getContentLength();
          }
        };
    request.addHeader("X-MateData-Request", "1");
    request.setServletPath("/api/v1/auth/login");
    request.setContentType("application/json");
    request.setContent(body);
    return request;
  }

  @Test
  void rejectsOversizedDeclaredAndChunkedBodiesBeforeDispatch() throws Exception {
    for (boolean unknownLength : new boolean[] {false, true}) {
      var reachedController = new AtomicBoolean();
      var response = new MockHttpServletResponse();
      new SessionGuard()
          .doFilter(
              request(unknownLength, new byte[256 * 1024 + 1]),
              response,
              (req, res) -> reachedController.set(true));
      assertThat(response.getStatus()).isEqualTo(413);
      assertThat(response.getContentAsString()).contains("PAYLOAD_TOO_LARGE");
      assertThat(reachedController).isFalse();
    }
  }

  @Test
  void preservesUtf8BodyForDownstreamJsonParsing() throws Exception {
    var body =
        "{\"username\":\"测试用户\",\"password\":\"local-test-value\"}"
            .getBytes(StandardCharsets.UTF_8);
    var reachedController = new AtomicBoolean();
    new SessionGuard()
        .doFilter(
            request(true, body),
            new MockHttpServletResponse(),
            (req, res) -> {
              assertThat(req.getInputStream().readAllBytes()).isEqualTo(body);
              reachedController.set(true);
            });
    assertThat(reachedController).isTrue();
  }
}
