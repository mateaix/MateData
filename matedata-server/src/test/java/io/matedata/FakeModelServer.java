package io.matedata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.matedata.harness.application.ModelSettings;
import io.matedata.harness.infrastructure.JdbcModelConfigurationRepository;
import io.matedata.shared.infrastructure.DocumentStore;
import io.matedata.shared.infrastructure.SecretVault;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Local stand-in for a model provider: scripted assistant replies over the real wire protocol. */
final class FakeModelServer implements AutoCloseable {
  enum Protocol {
    OPENAI_COMPATIBLE,
    OLLAMA
  }

  /** Returns the assistant message for a request; {@code call} counts from 1. */
  interface Script {
    Map<String, Object> reply(JsonNode request, int call) throws Exception;
  }

  static final ObjectMapper JSON = new ObjectMapper();
  final List<JsonNode> requests = new CopyOnWriteArrayList<>();
  private final HttpServer server;
  private final Protocol protocol;
  private final java.util.concurrent.ExecutorService workers =
      Executors.newVirtualThreadPerTaskExecutor();

  FakeModelServer(Protocol protocol, Script script) throws Exception {
    this.protocol = protocol;
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(workers);
    server.createContext(
        protocol == Protocol.OLLAMA ? "/api/chat" : "/v1/chat/completions",
        exchange -> {
          try {
            var request = JSON.readTree(exchange.getRequestBody().readAllBytes());
            requests.add(request);
            var message = script.reply(request, requests.size());
            boolean toolCall = message.containsKey("tool_calls");
            Object body =
                protocol == Protocol.OLLAMA
                    ? Map.of(
                        "model",
                        "test-model",
                        "created_at",
                        "2026-09-25T00:00:00Z",
                        "message",
                        message,
                        "done",
                        true,
                        "done_reason",
                        "stop",
                        "prompt_eval_count",
                        80,
                        "eval_count",
                        20)
                    : Map.of(
                        "id",
                        "chatcmpl-" + requests.size(),
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
                                toolCall ? "tool_calls" : "stop")),
                        "usage",
                        Map.of("prompt_tokens", 80, "completion_tokens", 20, "total_tokens", 100));
            byte[] bytes = JSON.writeValueAsBytes(body);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
          } catch (Exception e) {
            exchange.sendResponseHeaders(500, -1);
          } finally {
            exchange.close();
          }
        });
    server.start();
  }

  String baseUrl() {
    String host = "http://127.0.0.1:" + server.getAddress().getPort();
    return protocol == Protocol.OLLAMA ? host : host + "/v1";
  }

  /** An assistant turn that calls one tool; arguments follow each protocol's encoding. */
  Map<String, Object> toolCall(String id, String name, Map<String, Object> arguments)
      throws Exception {
    Object encoded = protocol == Protocol.OLLAMA ? arguments : JSON.writeValueAsString(arguments);
    var function = new LinkedHashMap<String, Object>();
    function.put("name", name);
    function.put("arguments", encoded);
    var call = new LinkedHashMap<String, Object>();
    if (protocol == Protocol.OPENAI_COMPATIBLE) {
      // Providers issue a fresh id per call; AgentScope treats a repeated id as already answered.
      call.put("id", id + "_" + UUID.randomUUID());
      call.put("type", "function");
    }
    call.put("function", function);
    return Map.of("role", "assistant", "content", "", "tool_calls", List.of(call));
  }

  static Map<String, Object> text(String content) {
    return Map.of("role", "assistant", "content", content);
  }

  /** Messages of a request whose role matches, in order. */
  static List<JsonNode> messages(JsonNode request, String role) {
    var found = new ArrayList<JsonNode>();
    for (var message : request.path("messages"))
      if (message.path("role").asText().equals(role)) found.add(message);
    return found;
  }

  static String content(JsonNode message) {
    var content = message.path("content");
    if (content.isTextual()) return content.asText();
    var text = new StringBuilder();
    for (var part : content) text.append(part.path("text").asText());
    return text.toString();
  }

  /** Tool results after the latest user message: progress within the current question. */
  static int toolResultsThisTurn(JsonNode request) {
    int count = 0;
    for (var message : request.path("messages")) {
      String role = message.path("role").asText();
      if (role.equals("user")) count = 0;
      else if (role.equals("tool")) count++;
    }
    return count;
  }

  static boolean hasToolResult(JsonNode request) {
    return !messages(request, "tool").isEmpty();
  }

  ModelSettings settings(Path dir, int steps, int timeout) throws Exception {
    var store =
        new DocumentStore(
            new JdbcTemplate(
                new DriverManagerDataSource(
                    "jdbc:h2:mem:model_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "")));
    var settings =
        new ModelSettings(
            new JdbcModelConfigurationRepository(store), new SecretVault(dir.toString(), ""));
    settings.save(
        protocol.name(),
        baseUrl(),
        "test-model",
        protocol == Protocol.OLLAMA ? "" : "private-test-key",
        steps,
        timeout);
    return settings;
  }

  @Override
  public void close() {
    server.stop(0);
    workers.shutdownNow();
  }
}
