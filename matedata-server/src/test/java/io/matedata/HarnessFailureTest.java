package io.matedata;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.matedata.harness.ExecutionStep;
import io.matedata.harness.QueryAgent;
import io.matedata.harness.application.ModelSettings;
import io.matedata.harness.infrastructure.*;
import io.matedata.semantic.SemanticModel;
import io.matedata.shared.infrastructure.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class HarnessFailureTest {
  @TempDir Path dir;
  final ObjectMapper json = new ObjectMapper();

  /** A governed query that must never run: every scenario here fails before data access. */
  static final QueryAgent.GovernedQuery NEVER =
      plan -> {
        throw new AssertionError("governed query must not run");
      };

  static QueryAgent.Turn turn(String runId) {
    return new QueryAgent.Turn("销售额", SemanticModel.sales(), "alice", runId, runId);
  }

  AgentScopeQueryAgent planner(HttpServer server, int steps, int timeout) throws Exception {
    var store =
        new DocumentStore(
            new JdbcTemplate(
                new DriverManagerDataSource(
                    "jdbc:h2:mem:failure_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "")));
    var settings =
        new ModelSettings(
            new JdbcModelConfigurationRepository(store), new SecretVault(dir.toString(), ""));
    settings.save(
        "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
        "test-model",
        "private-test-key",
        steps,
        timeout);
    return new AgentScopeQueryAgent(settings, dir.toString());
  }

  @Test
  void invalidPlanCannotBecomeSuccessAndIterationsAreBounded() throws Exception {
    var calls = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          int n = calls.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          var message =
              Map.of(
                  "role",
                  "assistant",
                  "tool_calls",
                  List.of(
                      Map.of(
                          "id",
                          "call_" + n,
                          "type",
                          "function",
                          "function",
                          Map.of(
                              "name",
                              "run_query",
                              "arguments",
                              json.writeValueAsString(
                                  Map.of(
                                      "metric",
                                      "secret_metric",
                                      "dimension",
                                      "region",
                                      "filtersJson",
                                      "{}",
                                      "limit",
                                      10))))));
          var payload =
              json.writeValueAsBytes(
                  Map.of(
                      "id",
                      "test_" + n,
                      "object",
                      "chat.completion",
                      "created",
                      1700000000,
                      "model",
                      "test-model",
                      "choices",
                      List.of(
                          Map.of("index", 0, "message", message, "finish_reason", "tool_calls"))));
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, payload.length);
          exchange.getResponseBody().write(payload);
          exchange.close();
        });
    server.start();
    try {
      var steps = new CopyOnWriteArrayList<ExecutionStep>();
      var planner = planner(server, 2, 10);
      assertThatThrownBy(() -> planner.answer(turn("invalid-plan"), NEVER, steps::add))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(calls.get()).isBetween(1, 2);
      assertThat(steps)
          .anySatisfy(
              step -> {
                assertThat(step.name()).isEqualTo("语义工具");
                assertThat(step.status()).isEqualTo("FAILED");
              });
      assertThat(steps.getLast().status()).isEqualTo("FAILED");
      assertThat(steps.toString()).doesNotContain("private-test-key", "secret_metric");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void providerFailureProducesSanitizedFailureTrace() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var calls = new AtomicInteger();
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          calls.incrementAndGet();
          exchange.getRequestBody().readAllBytes();
          byte[] payload =
              "{\"error\":{\"message\":\"private-provider-detail\"}}"
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(500, payload.length);
          exchange.getResponseBody().write(payload);
          exchange.close();
        });
    server.start();
    try {
      var steps = new CopyOnWriteArrayList<ExecutionStep>();
      var planner = planner(server, 2, 5);
      assertThatThrownBy(() -> planner.answer(turn("failed-provider"), NEVER, steps::add))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageNotContaining("private-provider-detail");
      assertThat(steps.getLast().status()).isEqualTo("FAILED");
      assertThat(steps.toString()).doesNotContain("private-test-key", "private-provider-detail");
      assertThat(calls.get()).isEqualTo(1);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void requestTimeoutCancelsPlanningWithoutReturningAPartialPlan() throws Exception {
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    var pool = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(pool);
    server.createContext(
        "/v1/chat/completions",
        exchange -> {
          try {
            Thread.sleep(10_000);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } finally {
            exchange.close();
          }
        });
    server.start();
    try {
      var steps = new CopyOnWriteArrayList<ExecutionStep>();
      var planner = planner(server, 2, 5);
      Assertions.assertTimeoutPreemptively(
          Duration.ofSeconds(8),
          () ->
              assertThatThrownBy(() -> planner.answer(turn("timed-out"), NEVER, steps::add))
                  .isInstanceOf(IllegalArgumentException.class));
      assertThat(steps.getLast().status()).isEqualTo("FAILED");
    } finally {
      server.stop(0);
      pool.shutdownNow();
    }
  }
}
