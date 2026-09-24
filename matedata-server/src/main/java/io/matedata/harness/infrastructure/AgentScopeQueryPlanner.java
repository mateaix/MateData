package io.matedata.harness.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.*;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.*;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.matedata.harness.ExecutionStep;
import io.matedata.harness.QueryPlanner;
import io.matedata.harness.application.ModelSettings;
import io.matedata.semantic.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

@Component
public class AgentScopeQueryPlanner implements QueryPlanner {
  private final ModelSettings settings;
  private final Path root;

  public AgentScopeQueryPlanner(ModelSettings settings, @Value("${matedata.data-dir}") String dir) {
    this.settings = settings;
    this.root = Path.of(dir).toAbsolutePath().resolve("agents");
  }

  public QueryPlan plan(String question, SemanticModel model, String userId, String runId) {
    return plan(question, model, userId, runId, ignored -> {});
  }

  @Override
  public QueryPlan plan(
      String question,
      SemanticModel model,
      String userId,
      String runId,
      Consumer<ExecutionStep> observer) {
    if (!settings.configured()) throw new IllegalArgumentException("请先配置模型 API Key");
    var config = settings.current();
    var collector = new PlanTool(model, observer);
    var toolkit = new Toolkit();
    toolkit.registerTool(collector);
    var admittedCalls = new AtomicInteger();
    var modelCalls = new AtomicInteger();
    var modelStart = new AtomicLong();
    var exhausted = new AtomicBoolean();
    long started = System.nanoTime();
    var chat =
        OpenAIChatModel.builder()
            .modelName(config.model())
            .apiKey(settings.key(config))
            .baseUrl(config.baseUrl())
            .stream(false)
            .generateOptions(
                GenerateOptions.builder()
                    .temperature(0.0)
                    .maxTokens(1500)
                    .executionConfig(
                        ExecutionConfig.builder()
                            .maxAttempts(1)
                            .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                            .build())
                    .build())
            .build();
    var callBudget =
        new MiddlewareBase() {
          @Override
          public Flux<AgentEvent> onModelCall(
              Agent agent,
              RuntimeContext context,
              ModelCallInput input,
              Function<ModelCallInput, Flux<AgentEvent>> next) {
            return Flux.defer(
                () -> {
                  if (admittedCalls.incrementAndGet() > config.maxSteps()) {
                    exhausted.set(true);
                    return Flux.error(new IllegalArgumentException("模型调用次数已达到预算上限"));
                  }
                  return next.apply(input);
                });
          }
        };
    try (var agent =
        HarnessAgent.builder()
            .name("matedata-query-planner")
            .model(chat)
            .toolkit(toolkit)
            .sysPrompt(
                "你是企业语义问数规划器。只使用下列已授权数据集的指标和维度。你必须调用 submit_query_plan 工具提交一个计划，不能编造字段或直接生成SQL。过滤值只允许等值筛选。不清楚问题含义时不要提交计划。维度可以为空字符串。数据集："
                    + new ObjectMapper().writeValueAsString(model))
            .workspace(root.resolve(userId).resolve(runId))
            .stateStore(new InMemoryAgentStateStore())
            .middleware(callBudget)
            .maxIters(config.maxSteps())
            .maxRetries(1)
            .enableAgentTracingLog(false)
            .disableFilesystemTools()
            .disableShellTool()
            .disableSubagents()
            .disableDynamicSubagents()
            .disableMemoryTools()
            .disableMemoryHooks()
            .disableDynamicSkills()
            .disableDefaultWorkspaceSkills()
            .disableWorkspaceContext()
            .disableAtPathExpansion()
            .disableCompaction()
            .disableTranscript()
            .disableSessionPersistence()
            .disableToolsConfig()
            .enableMetaTool(false)
            .enableTaskList(false)
            .build()) {
      // Harness includes platform/web helpers by default: enforce a final explicit tool allowlist.
      for (var name : List.copyOf(agent.getToolkit().getToolNames()))
        if (!name.equals("submit_query_plan")) agent.getToolkit().removeTool(name);
      var context = RuntimeContext.builder().userId(userId).sessionId(runId).build();
      agent
          .streamEvents(new UserMessage(question), context)
          .doOnNext(
              event -> {
                switch (event.getType()) {
                  case MODEL_CALL_START -> {
                    modelCalls.incrementAndGet();
                    modelStart.set(System.nanoTime());
                  }
                  case MODEL_CALL_END -> {
                    var usage = ((ModelCallEndEvent) event).getUsage();
                    String detail =
                        "完成模型调用"
                            + (usage == null
                                ? "；供应商未返回 token 用量"
                                : "；输入 "
                                    + usage.getInputTokens()
                                    + " / 输出 "
                                    + usage.getOutputTokens()
                                    + " tokens");
                    observer.accept(
                        new ExecutionStep(
                            "模型调用 #" + modelCalls.get(),
                            "SUCCEEDED",
                            detail,
                            elapsed(modelStart.get())));
                  }
                  case EXCEED_MAX_ITERS -> exhausted.set(true);
                  default -> {
                    /* Never retain model text, reasoning, prompts or raw tool arguments. */
                  }
                }
              })
          .blockLast(Duration.ofSeconds(config.timeoutSeconds()));
      if (exhausted.get()) throw new IllegalArgumentException("模型超过最大执行步数，请明确问题后重试");
      var result = collector.result.get();
      if (result == null) throw new IllegalArgumentException("模型未提交有效语义计划，请明确指标和维度后重试");
      observer.accept(
          new ExecutionStep(
              "Harness 结束",
              "SUCCEEDED",
              "已完成 " + modelCalls.get() + " 次模型调用；语义计划已通过校验",
              elapsed(started)));
      return result;
    } catch (Exception e) {
      observer.accept(
          new ExecutionStep(
              "Harness 结束",
              "FAILED",
              exhausted.get() ? "达到执行步数上限" : "模型调用失败、超时或未产生有效计划",
              elapsed(started)));
      if (e instanceof IllegalArgumentException known) throw known;
      throw new IllegalArgumentException("模型调用失败或超过执行预算，请检查模型设置后重试");
    }
  }

  private static long elapsed(long start) {
    return (System.nanoTime() - start) / 1_000_000;
  }

  public static final class PlanTool {
    private final SemanticModel model;
    private final AtomicReference<QueryPlan> result = new AtomicReference<>();
    private final Consumer<ExecutionStep> observer;

    PlanTool(SemanticModel model, Consumer<ExecutionStep> observer) {
      this.model = model;
      this.observer = observer;
    }

    @Tool(
        name = "submit_query_plan",
        description =
            "提交语义查询计划。metric 和 dimension 必须是已授权模型里的 id。filtersJson 是维度id到字符串等值过滤值的JSON对象。最多1000行。")
    public String submit(
        @ToolParam(name = "metric", description = "指标 id") String metric,
        @ToolParam(name = "dimension", description = "维度 id；总计用空字符串") String dimension,
        @ToolParam(name = "filtersJson", description = "JSON对象，例如 {} 或 {\"region\":\"华东\"}")
            String filtersJson,
        @ToolParam(name = "limit", description = "结果上限1至1000") int limit) {
      long start = System.nanoTime();
      try {
        if (filtersJson == null || filtersJson.length() > 2000)
          throw new IllegalArgumentException("过滤条件过长");
        Map<String, String> filters =
            new ObjectMapper().readValue(filtersJson, new TypeReference<>() {});
        var plan = new QueryPlan(metric, dimension, filters, limit);
        var query = new SemanticCompiler().compile(model, plan);
        new SqlGuard().verify(query, model, plan);
        if (!result.compareAndSet(null, plan)) throw new IllegalArgumentException("本轮已提交计划");
        observer.accept(
            new ExecutionStep(
                "语义工具",
                "SUCCEEDED",
                "submit_query_plan：指标 "
                    + metric
                    + "，维度 "
                    + Objects.toString(dimension, "")
                    + "；过滤条件 "
                    + filters.size()
                    + " 个",
                elapsed(start)));
        return "语义计划通过校验，已提交。请结束本轮。";
      } catch (Exception e) {
        observer.accept(
            new ExecutionStep("语义工具", "FAILED", "submit_query_plan：计划被校验器拒绝", elapsed(start)));
        return "计划被拒绝：请检查指标、维度、过滤值和结果上限。";
      }
    }
  }
}
