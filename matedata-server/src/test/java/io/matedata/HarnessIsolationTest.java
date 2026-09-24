package io.matedata;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.matedata.harness.application.ModelSettings;
import io.matedata.harness.infrastructure.AgentScopeQueryPlanner;
import io.matedata.harness.infrastructure.JdbcModelConfigurationRepository;
import io.matedata.semantic.SemanticModel;
import io.matedata.shared.infrastructure.DocumentStore;
import io.matedata.shared.infrastructure.SecretVault;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class HarnessIsolationTest {
  @TempDir Path dir;

  @Test
  @Timeout(40)
  void eightConcurrentRealHarnessSessionsKeepPlansAndPromptsSeparate() throws Exception {
    var json = new ObjectMapper();
    var arrivals = new CountDownLatch(8);
    var observedScopes = new ConcurrentHashMap<String, Integer>();
    var failures = new CopyOnWriteArrayList<Throwable>();
    try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
      var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.setExecutor(workers);
      server.createContext(
          "/v1/chat/completions",
          exchange -> {
            try {
              var request = json.readTree(exchange.getRequestBody().readAllBytes());
              var messages = request.path("messages");
              String scope = null;
              boolean hasToolResponse = false;
              for (var message : messages) {
                if (message.path("role").asText().equals("user")) {
                  var content = message.path("content");
                  String text =
                      content.isTextual() ? content.asText() : content.get(0).path("text").asText();
                  assertThat(text).matches("scope-[0-7]");
                  if (scope != null) assertThat(text).isEqualTo(scope);
                  scope = text;
                }
                hasToolResponse |= message.path("role").asText().equals("tool");
              }
              assertThat(scope).isNotNull();
              observedScopes.merge(scope, 1, Integer::sum);
              if (!hasToolResponse) {
                arrivals.countDown();
                assertThat(arrivals.await(15, TimeUnit.SECONDS)).isTrue();
              }
              Map<String, Object> message;
              if (hasToolResponse) {
                message = Map.of("role", "assistant", "content", "计划已提交");
              } else {
                var arguments =
                    Map.of(
                        "metric",
                        "revenue",
                        "dimension",
                        "region",
                        "filtersJson",
                        json.writeValueAsString(Map.of("region", scope)),
                        "limit",
                        10);
                message =
                    Map.of(
                        "role",
                        "assistant",
                        "tool_calls",
                        List.of(
                            Map.of(
                                "id",
                                "call_" + scope,
                                "type",
                                "function",
                                "function",
                                Map.of(
                                    "name",
                                    "submit_query_plan",
                                    "arguments",
                                    json.writeValueAsString(arguments)))));
              }
              byte[] response =
                  json.writeValueAsBytes(
                      Map.of(
                          "id",
                          "chatcmpl-isolation",
                          "object",
                          "chat.completion",
                          "created",
                          1700000000,
                          "model",
                          "test-model",
                          "choices",
                          List.of(
                              Map.of(
                                  "index",
                                  0,
                                  "message",
                                  message,
                                  "finish_reason",
                                  hasToolResponse ? "stop" : "tool_calls"))));
              exchange.getResponseHeaders().set("Content-Type", "application/json");
              exchange.sendResponseHeaders(200, response.length);
              exchange.getResponseBody().write(response);
            } catch (Throwable failure) {
              failures.add(failure);
              exchange.sendResponseHeaders(500, -1);
            } finally {
              exchange.close();
            }
          });
      server.start();
      try {
        var store =
            new DocumentStore(
                new JdbcTemplate(
                    new DriverManagerDataSource(
                        "jdbc:h2:mem:isolation_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                        "sa",
                        "")));
        var settings =
            new ModelSettings(
                new JdbcModelConfigurationRepository(store), new SecretVault(dir.toString(), ""));
        settings.save(
            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
            "test-model",
            "local-test-key",
            4,
            25);
        var planner = new AgentScopeQueryPlanner(settings, dir.toString());
        var results = new ArrayList<Future<io.matedata.semantic.QueryPlan>>();
        for (int i = 0; i < 8; i++) {
          String scope = "scope-" + i;
          results.add(
              workers.submit(
                  () ->
                      planner.plan(scope, SemanticModel.sales(), "user-" + scope, "run-" + scope)));
        }
        for (int i = 0; i < results.size(); i++) {
          assertThat(results.get(i).get(30, TimeUnit.SECONDS).filters())
              .containsExactlyEntriesOf(Map.of("region", "scope-" + i));
        }
        assertThat(failures).isEmpty();
        assertThat(observedScopes).hasSize(8);
        assertThat(observedScopes.values()).allMatch(count -> count >= 1 && count <= 4);
      } finally {
        server.stop(0);
      }
    }
  }
}
