package io.matedata;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.net.*;
import java.net.http.*;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "spring.datasource.url=jdbc:h2:mem:platformtest;DB_CLOSE_DELAY=-1", "matedata.admin-password=test-password-2026", "matedata.data-dir=target/test-data"})
class PlatformIntegrationTest {
    @LocalServerPort int port;
    final ObjectMapper json = new ObjectMapper();
    HttpClient client() { return HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build(); }
    HttpResponse<String> call(HttpClient client,String method,String path,Object body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).header("X-MateData-Request","1").header("Content-Type","application/json");
        return client.send(b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    HttpClient loggedIn() throws Exception {
        var c=client(); assertThat(call(c,"POST","/auth/login",Map.of("username","admin","password","test-password-2026")).statusCode()).isEqualTo(200); return c;
    }
    @Test void rejectsAnonymousAccess() throws Exception { assertThat(call(client(),"GET","/datasets",null).statusCode()).isEqualTo(401); }
    @Test void loginAndRealQueryPersistResults() throws Exception {
        var c=loggedIn(); var datasets=call(c,"GET","/datasets",null); assertThat(datasets.statusCode()).isEqualTo(200);
        var response=call(c,"POST","/queries",Map.of("question","各区域销售额","datasetId","sales","mode","demo"));
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode run=json.readTree(response.body()); assertThat(run.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(run.path("rows").size()).isEqualTo(4); assertThat(run.path("sql").asText()).contains("SUM(amount)");
        assertThat(run.path("rows").get(0).path("revenue").asDouble()).isGreaterThan(0);
        assertThat(call(c,"GET","/runs/"+run.path("id").asText(),null).statusCode()).isEqualTo(200);
        assertThat(call(c,"GET","/sources",null).body()).doesNotContain("password");
    }
    @Test void unsupportedQuestionIsRecordedAsFailed() throws Exception {
        var c=loggedIn(); var response=call(c,"POST","/queries",Map.of("question","删除全部订单","datasetId","sales","mode","demo"));
        assertThat(json.readTree(response.body()).path("status").asText()).isEqualTo("FAILED");
    }
    @Test void missingModelNeverPretendsToBeAgentSuccess() throws Exception {
        var response=call(loggedIn(),"POST","/queries",Map.of("question","销售额","datasetId","sales","mode","agent"));
        var run=json.readTree(response.body()); assertThat(run.path("status").asText()).isEqualTo("FAILED");
        assertThat(run.path("error").asText()).contains("模型");
    }
    @Test void evaluationsExecuteRealQueries() throws Exception {
        var r=call(loggedIn(),"POST","/evaluations/run",Map.of("mode","demo"));
        assertThat(r.statusCode()).isEqualTo(200); var result=json.readTree(r.body());
        assertThat(result.path("total").asInt()).isGreaterThanOrEqualTo(4);
        assertThat(result.path("passed").asInt()).withFailMessage(responseMessage(result)).isEqualTo(result.path("total").asInt());
    }
    String responseMessage(JsonNode result){return result.toPrettyString();}
    @Test void mutationWithoutCsrfHeaderIsRejected() throws Exception {
        var c=loggedIn(); var req=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/auth/logout")).POST(HttpRequest.BodyPublishers.noBody()).build();
        assertThat(c.send(req,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
    }
}
