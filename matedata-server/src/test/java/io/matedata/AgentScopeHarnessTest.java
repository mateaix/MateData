package io.matedata;

import com.sun.net.httpserver.HttpServer;
import io.matedata.harness.application.ModelSettings;
import io.matedata.harness.infrastructure.AgentScopeQueryPlanner;
import io.matedata.shared.infrastructure.*;
import io.matedata.semantic.SemanticModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

class AgentScopeHarnessTest {
    @TempDir Path dir;
    @Test void realHarnessCallsProviderAndOnlyExposesSemanticTool()throws Exception {
        var requests=new CopyOnWriteArrayList<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{
            requests.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            String message=requests.size()==1?"{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call_plan\",\"type\":\"function\",\"function\":{\"name\":\"submit_query_plan\",\"arguments\":\"{\\\"metric\\\":\\\"revenue\\\",\\\"dimension\\\":\\\"region\\\",\\\"filtersJson\\\":\\\"{}\\\",\\\"limit\\\":10}\"}}]}":"{\"role\":\"assistant\",\"content\":\"已提交语义查询计划\"}";
            String response="{\"id\":\"chatcmpl-test\",\"object\":\"chat.completion\",\"created\":1700000000,\"model\":\"test-model\",\"choices\":[{\"index\":0,\"message\":"+message+",\"finish_reason\":\""+(requests.size()==1?"tool_calls":"stop")+"\"}],\"usage\":{\"prompt_tokens\":80,\"completion_tokens\":20,\"total_tokens\":100}}";
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            var ds=new DriverManagerDataSource("jdbc:h2:mem:harness_"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
            var store=new DocumentStore(new JdbcTemplate(ds));var vault=new SecretVault(dir.toString(),"");var settings=new ModelSettings(store,vault);
            settings.save("http://127.0.0.1:"+server.getAddress().getPort()+"/v1","test-model","fake-test-key",4,15);
            var planner=new AgentScopeQueryPlanner(settings,dir.toString());
            var plan=planner.plan("各区域销售额",SemanticModel.sales(),"alice","run-one");
            assertThat(plan.metric()).isEqualTo("revenue");assertThat(plan.dimension()).isEqualTo("region");assertThat(requests.size()).isBetween(1,4);
            var json=new ObjectMapper();
            for(String request:requests){var tools=json.readTree(request).path("tools");assertThat(tools.size()).isEqualTo(1);assertThat(tools.get(0).path("function").path("name").asText()).isEqualTo("submit_query_plan");}
            assertThat(settings.view().toString()).doesNotContain("fake-test-key");
        }finally{server.stop(0);}
    }
}
