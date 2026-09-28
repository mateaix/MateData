package io.matedata;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.*;
import java.net.http.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:platformtest;DB_CLOSE_DELAY=-1",
      "matedata.admin-password=test-password-2026",
      "matedata.data-dir=target/test-data"
    })
class PlatformIntegrationTest {
  @LocalServerPort int port;
  final ObjectMapper json = new ObjectMapper();

  HttpClient client() {
    return HttpClient.newBuilder()
        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
        .build();
  }

  HttpResponse<String> call(HttpClient client, String method, String path, Object body)
      throws Exception {
    var b =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
            .header("X-MateData-Request", "1")
            .header("Content-Type", "application/json");
    return client.send(
        b.method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  HttpClient loggedIn() throws Exception {
    var c = client();
    assertThat(
            call(
                    c,
                    "POST",
                    "/auth/login",
                    Map.of("username", "admin", "password", "test-password-2026"))
                .statusCode())
        .isEqualTo(200);
    return c;
  }

  @Test
  void queryIdempotencyHeaderReplaysTheSameRunAndRejectsPayloadChanges() throws Exception {
    var client = loggedIn();
    String key = java.util.UUID.randomUUID().toString();
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/queries"))
            .header("X-MateData-Request", "1")
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", key);
    String body =
        json.writeValueAsString(Map.of("question", "各区域销售额", "datasetId", "sales", "mode", "demo"));
    var first =
        client.send(
            request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    var second =
        client.send(
            request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(first.statusCode()).isEqualTo(200);
    assertThat(second.statusCode()).isEqualTo(200);
    assertThat(json.readTree(second.body()).path("id"))
        .isEqualTo(json.readTree(first.body()).path("id"));
    var conflict =
        client.send(
            request
                .POST(HttpRequest.BodyPublishers.ofString(body.replace("各区域销售额", "各品类利润")))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertThat(conflict.statusCode()).isEqualTo(409);
  }

  @Test
  void rejectsChunkedOversizedJsonBeforeLoginProcessing() throws Exception {
    byte[] body = new byte[256 * 1024 + 1];
    var request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/login"))
            .header("X-MateData-Request", "1")
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofInputStream(
                    () -> new java.io.ByteArrayInputStream(body)))
            .build();
    var response = client().send(request, HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(413);
    assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("PAYLOAD_TOO_LARGE");
  }

  @Test
  void rejectsAnonymousAccess() throws Exception {
    assertThat(call(client(), "GET", "/datasets", null).statusCode()).isEqualTo(401);
  }

  @Test
  void loginAndRealQueryPersistResults() throws Exception {
    var c = loggedIn();
    var datasets = call(c, "GET", "/datasets", null);
    assertThat(datasets.statusCode()).isEqualTo(200);
    var response =
        call(
            c,
            "POST",
            "/queries",
            Map.of("question", "各区域销售额", "datasetId", "sales", "mode", "demo"));
    assertThat(response.statusCode()).isEqualTo(200);
    JsonNode run = json.readTree(response.body());
    assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(run.path("rows").size()).isEqualTo(4);
    assertThat(run.path("sql").asText()).contains("SUM(amount)");
    assertThat(run.path("rows").get(0).path("revenue").asDouble()).isGreaterThan(0);
    assertThat(call(c, "GET", "/runs/" + run.path("id").asText(), null).statusCode())
        .isEqualTo(200);
    assertThat(call(c, "GET", "/sources", null).body()).doesNotContain("password");
  }

  @Test
  void unsupportedQuestionIsRecordedAsFailed() throws Exception {
    var c = loggedIn();
    var response =
        call(
            c,
            "POST",
            "/queries",
            Map.of("question", "删除全部订单", "datasetId", "sales", "mode", "demo"));
    assertThat(json.readTree(response.body()).path("status").asText()).isEqualTo("FAILED");
  }

  @Test
  void missingModelNeverPretendsToBeAgentSuccess() throws Exception {
    var response =
        call(
            loggedIn(),
            "POST",
            "/queries",
            Map.of("question", "销售额", "datasetId", "sales", "mode", "agent"));
    var run = json.readTree(response.body());
    assertThat(run.path("status").asText()).isEqualTo("FAILED");
    assertThat(run.path("error").asText()).contains("模型");
  }

  @Test
  void evaluationsExecuteRealQueries() throws Exception {
    var r = call(loggedIn(), "POST", "/evaluations/run", Map.of("mode", "demo"));
    assertThat(r.statusCode()).isEqualTo(200);
    var result = json.readTree(r.body());
    assertThat(result.path("total").asInt()).isGreaterThanOrEqualTo(4);
    assertThat(result.path("passed").asInt())
        .withFailMessage(responseMessage(result))
        .isEqualTo(result.path("total").asInt());
    assertThat(
            call(loggedIn(), "GET", "/evaluations/reports/" + result.path("id").asText(), null)
                .statusCode())
        .isEqualTo(200);
  }

  String responseMessage(JsonNode result) {
    return result.toPrettyString();
  }

  @Test
  void mutationWithoutCsrfHeaderIsRejected() throws Exception {
    var c = loggedIn();
    var req =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/auth/logout"))
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();
    assertThat(c.send(req, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
  }

  @Test
  void datasetGrantsEnforceMetricAndRowScopeAndHistoryOwnership() throws Exception {
    var admin = loggedIn();
    String username =
        "analyst_" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    assertThat(
            call(
                    admin,
                    "POST",
                    "/users",
                    Map.of(
                        "username",
                        username,
                        "displayName",
                        "区域分析师",
                        "role",
                        "ANALYST",
                        "password",
                        "analyst-test-2026"))
                .statusCode())
        .isEqualTo(200);
    var model = json.readTree(call(admin, "GET", "/datasets", null).body()).get(0).deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) model)
        .put("id", "restricted_sales")
        .put("name", "受限销售分析");
    assertThat(call(admin, "POST", "/datasets", model).statusCode()).isEqualTo(200);
    var analyst = client();
    assertThat(
            call(
                    analyst,
                    "POST",
                    "/auth/login",
                    Map.of("username", username, "password", "analyst-test-2026"))
                .statusCode())
        .isEqualTo(200);
    var question = Map.of("question", "各区域销售额", "datasetId", "restricted_sales", "mode", "demo");
    assertThat(call(analyst, "POST", "/queries", question).statusCode()).isEqualTo(403);
    assertThat(call(analyst, "GET", "/datasets", null).body()).doesNotContain("restricted_sales");
    assertThat(
            call(
                    admin,
                    "PUT",
                    "/permissions/" + username + "/restricted_sales",
                    Map.of(
                        "enabled",
                        true,
                        "metrics",
                        java.util.List.of("revenue"),
                        "dimensions",
                        java.util.List.of("region"),
                        "rowFilters",
                        Map.of("region", "华东")))
                .statusCode())
        .isEqualTo(200);
    var run = json.readTree(call(analyst, "POST", "/queries", question).body());
    assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(run.path("rows").size()).isEqualTo(1);
    assertThat(run.path("rows").get(0).path("region").asText()).isEqualTo("华东");
    var visible = json.readTree(call(analyst, "GET", "/datasets", null).body());
    JsonNode scopedDataset = null;
    for (var dataset : visible)
      if (dataset.path("id").asText().equals("restricted_sales")) scopedDataset = dataset;
    assertThat(scopedDataset).isNotNull();
    String fingerprint = scopedDataset.path("scopeFingerprint").asText();
    assertThat(fingerprint).matches("[a-f0-9]{64}");
    assertThat(run.path("scopeFingerprint").asText()).isEqualTo(fingerprint);
    assertThat(
            json.readTree(call(analyst, "GET", "/runs/" + run.path("id").asText(), null).body())
                .path("scopeFingerprint")
                .asText())
        .isEqualTo(fingerprint);
    assertThat(
            call(
                    admin,
                    "PUT",
                    "/permissions/" + username + "/restricted_sales",
                    Map.of(
                        "enabled",
                        true,
                        "metrics",
                        java.util.List.of("revenue"),
                        "dimensions",
                        java.util.List.of("region"),
                        "rowFilters",
                        Map.of("region", "华南")))
                .statusCode())
        .isEqualTo(200);
    var changed = json.readTree(call(analyst, "GET", "/datasets", null).body());
    for (var dataset : changed)
      if (dataset.path("id").asText().equals("restricted_sales")) {
        assertThat(dataset.path("scopeFingerprint").asText()).isNotEqualTo(fingerprint);
        assertThat(dataset.path("metrics")).isEqualTo(scopedDataset.path("metrics"));
        assertThat(dataset.has("rowFilters")).isFalse();
      }
    assertThat(call(analyst, "GET", "/runs/" + run.path("id").asText(), null).statusCode())
        .isEqualTo(403);
    assertThat(call(admin, "GET", "/runs/" + run.path("id").asText(), null).statusCode())
        .isEqualTo(404);
    assertThat(call(analyst, "GET", "/sources", null).statusCode()).isEqualTo(403);
    var rejected =
        json.readTree(
            call(
                    analyst,
                    "POST",
                    "/queries",
                    Map.of("question", "各区域利润", "datasetId", "restricted_sales", "mode", "demo"))
                .body());
    assertThat(rejected.path("status").asText()).isEqualTo("FAILED");
    assertThat(
            call(
                    admin,
                    "PUT",
                    "/permissions/" + username + "/restricted_sales",
                    Map.of(
                        "enabled",
                        false,
                        "metrics",
                        java.util.List.of("revenue"),
                        "dimensions",
                        java.util.List.of("region"),
                        "rowFilters",
                        Map.of()))
                .statusCode())
        .isEqualTo(200);
    assertThat(call(analyst, "GET", "/runs/" + run.path("id").asText(), null).statusCode())
        .isEqualTo(403);
    assertThat(call(analyst, "GET", "/runs", null).body()).doesNotContain(run.path("id").asText());
  }

  @Test
  void frameworkErrorsKeepTheirHttpMeaning() throws Exception {
    var c = loggedIn();
    assertThat(call(c, "GET", "/queries", null).statusCode()).isEqualTo(405);
    assertThat(call(c, "GET", "/runs/page?limit=oops", null).statusCode()).isEqualTo(400);
    assertThat(call(c, "GET", "/route-that-does-not-exist", null).statusCode()).isEqualTo(404);
  }
}
