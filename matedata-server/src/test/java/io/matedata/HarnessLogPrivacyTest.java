package io.matedata;

import static org.assertj.core.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.matedata.harness.application.ModelSettings;
import io.matedata.harness.infrastructure.AgentScopeQueryPlanner;
import io.matedata.semantic.SemanticModel;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:logprivacy;DB_CLOSE_DELAY=-1",
      "matedata.admin-password=privacy-test-password",
      "matedata.data-dir=target/privacy-data"
    })
@ExtendWith(OutputCaptureExtension.class)
class HarnessLogPrivacyTest {
  @Autowired ModelSettings settings;
  @Autowired AgentScopeQueryPlanner planner;

  @Test
  void providerErrorPayloadsNeverReachApplicationLogs(CapturedOutput output) throws Exception {
    String marker = "provider-sensitive-payload-must-not-be-logged";
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          exchange.getRequestBody().readAllBytes();
          byte[] body =
              ("{\"error\":{\"message\":\"" + marker + "\"}}").getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(500, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      settings.save(
          "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
          "test-model",
          "private-test-key",
          1,
          5);
      assertThatThrownBy(
              () -> planner.plan("各区域销售额", SemanticModel.sales(), "admin", "privacy-run"))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(output.getAll()).doesNotContain(marker, "private-test-key");
    } finally {
      server.stop(0);
    }
  }
}
