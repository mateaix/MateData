package io.matedata.harness.infrastructure;
import io.matedata.harness.QueryPlanner;
import io.matedata.semantic.*;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import io.matedata.harness.application.ModelSettings;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.*;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@Component public class AgentScopeQueryPlanner implements QueryPlanner {
    private final ModelSettings settings;private final Path root;
    public AgentScopeQueryPlanner(ModelSettings settings,@Value("${matedata.data-dir}") String dir){this.settings=settings;this.root=Path.of(dir).toAbsolutePath().resolve("agents");}
    public QueryPlan plan(String question,SemanticModel model,String userId,String runId){
        if(!settings.configured())throw new IllegalArgumentException("请先配置模型 API Key");
        var config=settings.current();var collector=new PlanTool(model);var toolkit=new Toolkit();toolkit.registerTool(collector);
        var chat=OpenAIChatModel.builder().modelName(config.model()).apiKey(settings.key(config)).baseUrl(config.baseUrl()).stream(false)
                .generateOptions(GenerateOptions.builder().temperature(0.0).maxTokens(1500).build()).build();
        try(var agent=HarnessAgent.builder().name("matedata-query-planner").model(chat).toolkit(toolkit)
                .sysPrompt("你是企业语义问数规划器。只使用下列已授权数据集的指标和维度。你必须调用 submit_query_plan 工具提交一个计划，不能编造字段或直接生成SQL。过滤值只允许等值筛选。不清楚问题含义时不要提交计划。维度可以为空字符串。数据集："+new ObjectMapper().writeValueAsString(model))
                .workspace(root.resolve(userId).resolve(runId)).stateStore(new InMemoryAgentStateStore())
                .maxIters(config.maxSteps()).maxRetries(1).enableAgentTracingLog(false)
                .disableFilesystemTools().disableShellTool().disableSubagents().disableDynamicSubagents()
                .disableMemoryTools().disableMemoryHooks().disableDynamicSkills().disableDefaultWorkspaceSkills()
                .disableWorkspaceContext().disableAtPathExpansion().disableCompaction().disableTranscript()
                .disableSessionPersistence().disableToolsConfig().enableMetaTool(false).enableTaskList(false).build()) {
            // Harness includes platform/web helpers by default: enforce a final explicit tool allowlist.
            for(var name:List.copyOf(agent.getToolkit().getToolNames()))if(!name.equals("submit_query_plan"))agent.getToolkit().removeTool(name);
            var context=RuntimeContext.builder().userId(userId).sessionId(runId).build();
            agent.call(new UserMessage(question),context).block(Duration.ofSeconds(config.timeoutSeconds()));
            var result=collector.result.get();
            if(result==null)throw new IllegalArgumentException("模型未提交有效语义计划，请明确指标和维度后重试");
            return result;
        }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalArgumentException("模型调用失败或超过执行预算，请检查模型设置后重试");}
    }
    public static final class PlanTool {
        private final SemanticModel model;private final AtomicReference<QueryPlan> result=new AtomicReference<>();
        PlanTool(SemanticModel model){this.model=model;}
        @Tool(name="submit_query_plan",description="提交语义查询计划。metric 和 dimension 必须是已授权模型里的 id。filtersJson 是维度id到字符串等值过滤值的JSON对象。最多1000行。")
        public String submit(@ToolParam(name="metric",description="指标 id") String metric,
                             @ToolParam(name="dimension",description="维度 id；总计用空字符串") String dimension,
                             @ToolParam(name="filtersJson",description="JSON对象，例如 {} 或 {\"region\":\"华东\"}") String filtersJson,
                             @ToolParam(name="limit",description="结果上限1至1000") int limit) {
            try {
                if(filtersJson==null||filtersJson.length()>2000)throw new IllegalArgumentException("过滤条件过长");
                Map<String,String> filters=new ObjectMapper().readValue(filtersJson,new TypeReference<>(){});
                var plan=new QueryPlan(metric,dimension,filters,limit);var query=new SemanticCompiler().compile(model,plan);new SqlGuard().verify(query,model,plan);
                if(!result.compareAndSet(null,plan))throw new IllegalArgumentException("本轮已提交计划");return "语义计划通过校验，已提交。请结束本轮。";
            }catch(Exception e){return "计划被拒绝：请检查指标、维度、过滤值和结果上限。";}
        }
    }
}
