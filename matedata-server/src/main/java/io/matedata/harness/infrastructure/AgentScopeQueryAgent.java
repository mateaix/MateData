package io.matedata.harness.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.*;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.core.tool.*;
import io.agentscope.extensions.model.ollama.OllamaChatModel;
import io.agentscope.extensions.model.ollama.options.OllamaOptions;
import io.agentscope.extensions.model.ollama.options.ThinkOption;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;
import io.matedata.harness.ExecutionStep;
import io.matedata.harness.ModelConfiguration;
import io.matedata.harness.QueryAgent;
import io.matedata.harness.application.ModelSettings;
import io.matedata.semantic.*;
import io.matedata.shared.ApplicationException;
import java.math.BigDecimal;
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

/** AgentScope Harness adapter: semantic read tools, one governed query, then interpretation. */
@Component
public class AgentScopeQueryAgent implements QueryAgent {
  /** Questions per conversation before the user must start a new one; bounds model context. */
  static final int MAX_TURNS = 8;

  static final int MAX_ANSWER_CHARS = 2000;
  static final int MAX_ROWS_FOR_MODEL = 30;
  static final int MAX_RESULT_CHARS = 4000;
  static final List<String> TOOL_NAMES = List.of("list_metrics", "list_dimensions", "run_query");

  private final ModelSettings settings;
  private final Path root;
  private final AgentStateStore sessions;

  public AgentScopeQueryAgent(ModelSettings settings, @Value("${matedata.data-dir}") String dir) {
    this.settings = settings;
    Path data = Path.of(dir).toAbsolutePath();
    this.root = data.resolve("agents");
    // Conversation memory survives restarts next to the metadata database it belongs to.
    this.sessions = new JsonFileAgentStateStore(data.resolve("agent-sessions"));
  }

  @Override
  public Reply answer(Turn turn, GovernedQuery query, Consumer<ExecutionStep> observer) {
    if (!settings.configured()) throw new IllegalArgumentException("请先在模型设置中配置模型服务");
    if (priorTurns(turn) >= MAX_TURNS)
      throw new IllegalArgumentException("本对话已达 " + MAX_TURNS + " 轮上限，请新建对话后继续提问");
    var config = settings.current();
    var tools = new SemanticTools(turn.model(), query, observer);
    var toolkit = new Toolkit();
    toolkit.registerTool(tools);
    var admittedCalls = new AtomicInteger();
    var modelCalls = new AtomicInteger();
    var modelStart = new AtomicLong();
    var exhausted = new AtomicBoolean();
    var reply = new AtomicReference<String>("");
    long started = System.nanoTime();
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
            .name("matedata-query-agent")
            .model(model(config))
            .toolkit(toolkit)
            .sysPrompt(prompt(turn.model()))
            .workspace(root.resolve(turn.userId()).resolve(turn.runId()))
            .stateStore(sessions)
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
            .disableToolsConfig()
            .enableMetaTool(false)
            .enableTaskList(false)
            .build()) {
      // Harness includes platform/web helpers by default: enforce a final explicit tool allowlist.
      for (var name : List.copyOf(agent.getToolkit().getToolNames()))
        if (!TOOL_NAMES.contains(name)) agent.getToolkit().removeTool(name);
      var context =
          RuntimeContext.builder().userId(turn.userId()).sessionId(turn.sessionKey()).build();
      agent
          .streamEvents(new UserMessage(turn.question()), context)
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
                  case AGENT_RESULT -> reply.set(text(((AgentResultEvent) event).getResult()));
                  case EXCEED_MAX_ITERS -> exhausted.set(true);
                  default -> {
                    /* Never retain prompts, reasoning or raw tool arguments. */
                  }
                }
              })
          .blockLast(Duration.ofSeconds(config.timeoutSeconds()));
      tools.rethrowFatal();
      if (!tools.executed()) {
        if (exhausted.get()) throw new IllegalArgumentException("模型超过最大执行步数，请明确问题后重试");
        if (reply.get().isBlank()) throw new IllegalArgumentException("智能体未执行查询，请明确要分析的指标和维度后重试");
        // A clarifying question or a refusal: returned as text, never as data.
        observer.accept(
            new ExecutionStep(
                "Harness 结束",
                "SUCCEEDED",
                "已完成 " + modelCalls.get() + " 次模型调用；智能体需要补充信息，未执行查询",
                elapsed(started)));
        return new Reply(reply.get());
      }
      observer.accept(
          new ExecutionStep(
              "Harness 结束",
              "SUCCEEDED",
              "已完成 "
                  + modelCalls.get()
                  + " 次模型调用"
                  + (reply.get().isBlank() ? "；模型未给出解读，使用平台摘要" : "；已生成结果解读"),
              elapsed(started)));
      return new Reply(reply.get());
    } catch (Exception e) {
      observer.accept(
          new ExecutionStep(
              "Harness 结束",
              "FAILED",
              exhausted.get() ? "达到执行步数上限" : "模型调用失败、超时或未完成受治理查询",
              elapsed(started)));
      var fatal = tools.fatal();
      if (fatal != null) throw fatal;
      if (e instanceof IllegalArgumentException || e instanceof ApplicationException)
        throw (RuntimeException) e;
      throw new IllegalArgumentException("模型调用失败或超过执行预算，请检查模型设置后重试");
    }
  }

  private int priorTurns(Turn turn) {
    return sessions
        .get(turn.userId(), turn.sessionKey(), "agent_state", AgentState.class)
        .map(
            state ->
                (int)
                    state.getContext().stream()
                        .filter(
                            message ->
                                message.getRole() == MsgRole.USER
                                    && !message.getContentBlocks(TextBlock.class).isEmpty()
                                    && message.getContentBlocks(ToolResultBlock.class).isEmpty())
                        .count())
        .orElse(0);
  }

  private Model model(ModelConfiguration config) {
    var execution =
        ExecutionConfig.builder()
            .maxAttempts(1)
            .timeout(Duration.ofSeconds(config.timeoutSeconds()))
            .build();
    return switch (config.provider()) {
      case OPENAI_COMPATIBLE ->
          OpenAIChatModel.builder()
              .modelName(config.model())
              .apiKey(settings.key(config))
              .baseUrl(config.baseUrl())
              .stream(false)
              .generateOptions(
                  GenerateOptions.builder()
                      .temperature(0.0)
                      .maxTokens(1500)
                      .executionConfig(execution)
                      .build())
              .build();
      case OLLAMA ->
          OllamaChatModel.builder().modelName(config.model()).baseUrl(config.baseUrl()).stream(
                  false)
              .defaultOptions(
                  OllamaOptions.builder()
                      .temperature(0.0)
                      .numPredict(1500)
                      .numCtx(16384)
                      // Reasoning is never shown or stored; skipping it keeps local models fast.
                      .thinkOption(ThinkOption.ThinkBoolean.DISABLED)
                      .executionConfig(execution)
                      .build())
              .build();
    };
  }

  static String prompt(SemanticModel model) {
    return "你是 MateData 的企业问数智能体，只能通过工具获取数据。\n"
        + "1. 先调用 list_metrics 和 list_dimensions，了解当前用户已授权的指标与维度；只能使用返回的 id。\n"
        + "2. 调用 run_query 执行一次受治理查询：选择一个指标，至多一个分组维度；过滤条件只支持维度的等值筛选。"
        + "SQL 由平台生成并校验，你不能也不需要编写 SQL。\n"
        + "3. 根据 run_query 返回的数据，用简洁的中文回答问题：给出结论，引用具体数值，指出最高、最低或明显差异。"
        + "不得编造数据中没有的数值或原因。使用普通文本和换行，不要使用 Markdown 格式。\n"
        + "4. 追问时结合之前的对话：省略的指标、维度和筛选条件沿用上一轮查询，只替换用户提到的部分，然后重新调用 run_query。"
        + "例如上一轮按区域查销售额，追问“那利润呢”就按区域查利润。\n"
        + "5. 只有在确实无法判断用户意图时，才用一句话向用户确认，此时不要调用 run_query。"
        + "若问题与当前数据集无关，或现有指标无法回答，直接说明原因。\n"
        + "当前数据集："
        + model.name()
        + (model.description() == null || model.description().isBlank()
            ? ""
            : "（" + model.description() + "）");
  }

  private static String text(Msg message) {
    if (message == null) return "";
    var text = new StringBuilder();
    for (var block : message.getContentBlocks(TextBlock.class)) text.append(block.getText());
    String answer = plain(text.toString());
    return answer.length() > MAX_ANSWER_CHARS
        ? answer.substring(0, MAX_ANSWER_CHARS) + "…"
        : answer;
  }

  /** The UI shows answers as plain text: drop emphasis markers and normalize list bullets. */
  public static String plain(String text) {
    return text.replace("**", "")
        .replace("__", "")
        .replaceAll("(?m)^[ \\t]*[*+-][ \\t]+", "- ")
        .replaceAll("(?m)^#{1,6}[ \\t]+", "")
        .replaceAll("\\n{3,}", "\n\n")
        .strip();
  }

  private static long elapsed(long start) {
    return (System.nanoTime() - start) / 1_000_000;
  }

  /** Tools the model may call. Business vocabulary only: no tables, columns or sources. */
  public static final class SemanticTools {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final SemanticModel model;
    private final GovernedQuery query;
    private final Consumer<ExecutionStep> observer;
    private final AtomicBoolean executed = new AtomicBoolean();
    private final AtomicReference<RuntimeException> fatal = new AtomicReference<>();

    SemanticTools(SemanticModel model, GovernedQuery query, Consumer<ExecutionStep> observer) {
      this.model = model;
      this.query = query;
      this.observer = observer;
    }

    boolean executed() {
      return executed.get();
    }

    RuntimeException fatal() {
      return fatal.get();
    }

    void rethrowFatal() {
      if (fatal.get() != null) throw fatal.get();
    }

    @Tool(name = "list_metrics", description = "列出当前用户可查询的业务指标：id、名称、别名和聚合方式。")
    public String listMetrics() {
      long start = System.nanoTime();
      var metrics =
          model.metrics().stream()
              .map(
                  m ->
                      Map.of(
                          "id", m.id(),
                          "name", m.name(),
                          "aliases", m.aliases(),
                          "aggregation", m.aggregation()))
              .toList();
      observer.accept(
          new ExecutionStep(
              "语义工具", "SUCCEEDED", "list_metrics：" + metrics.size() + " 个已授权指标", elapsed(start)));
      return write(metrics);
    }

    @Tool(name = "list_dimensions", description = "列出当前用户可用于分组或筛选的维度：id、名称、别名和取值类型（日期使用 ISO 格式）。")
    public String listDimensions() {
      long start = System.nanoTime();
      var dimensions =
          model.dimensions().stream()
              .map(
                  d ->
                      Map.of(
                          "id", d.id(),
                          "name", d.name(),
                          "aliases", d.aliases(),
                          "valueType", d.valueType().name()))
              .toList();
      observer.accept(
          new ExecutionStep(
              "语义工具",
              "SUCCEEDED",
              "list_dimensions：" + dimensions.size() + " 个已授权维度",
              elapsed(start)));
      return write(dimensions);
    }

    @Tool(
        name = "run_query",
        description =
            "执行一次受治理的聚合查询并返回结果行。metric 为指标 id；dimension 为分组维度 id，不分组时传空字符串；"
                + "filtersJson 为维度 id 到等值筛选值的 JSON 对象，例如 {} 或 {\"region\":\"华东\"}；limit 为 1–1000。"
                + "每个问题只能成功执行一次。")
    public String runQuery(
        @ToolParam(name = "metric", description = "指标 id") String metric,
        @ToolParam(name = "dimension", description = "分组维度 id；总计时传空字符串") String dimension,
        @ToolParam(name = "filtersJson", description = "JSON 对象，例如 {} 或 {\"region\":\"华东\"}")
            String filtersJson,
        @ToolParam(name = "limit", description = "结果上限 1–1000") int limit) {
      long start = System.nanoTime();
      if (fatal.get() != null) return "查询已终止：数据权限或语义模型已变更。请停止调用工具。";
      if (executed.get()) return "本问题已完成查询，请直接根据已有结果回答，不要再次调用 run_query。";
      QueryPlan plan;
      try {
        if (filtersJson == null || filtersJson.length() > 2000)
          throw new IllegalArgumentException("过滤条件过长");
        Map<String, String> filters =
            filtersJson.isBlank()
                ? Map.of()
                : JSON.readValue(filtersJson, new TypeReference<>() {});
        plan = new QueryPlan(metric, dimension, filters, limit);
        // Validate against the permitted vocabulary before touching any data.
        new SqlGuard().verify(new SemanticCompiler().compile(model, plan), model, plan);
      } catch (Exception e) {
        observer.accept(new ExecutionStep("语义工具", "FAILED", "run_query：计划被校验器拒绝", elapsed(start)));
        return "查询被拒绝：请只使用 list_metrics / list_dimensions 返回的 id，并检查过滤值与结果上限。";
      }
      try {
        var rows = query.run(plan);
        executed.set(true);
        return describe(rows);
      } catch (ApplicationException e) {
        if (e.kind() == ApplicationException.Kind.FORBIDDEN) {
          fatal.compareAndSet(null, e);
          return "查询已终止：" + e.getMessage();
        }
        return "查询失败：" + e.getMessage();
      } catch (IllegalArgumentException e) {
        return "查询失败：" + e.getMessage();
      } catch (RuntimeException e) {
        fatal.compareAndSet(null, e);
        return "查询执行失败，请停止调用工具。";
      }
    }

    private static String describe(Rows rows) {
      var shown = new ArrayList<Map<String, Object>>();
      int chars = 0;
      for (var row : rows.rows()) {
        if (shown.size() >= MAX_ROWS_FOR_MODEL) break;
        var copy = new LinkedHashMap<String, Object>();
        row.forEach(
            (key, value) ->
                copy.put(
                    key, value instanceof BigDecimal decimal ? decimal.toPlainString() : value));
        chars += write(copy).length();
        if (chars > MAX_RESULT_CHARS) break;
        shown.add(copy);
      }
      var result = new LinkedHashMap<String, Object>();
      result.put("columns", rows.columns());
      result.put("rowCount", rows.rows().size());
      result.put("rows", shown);
      if (shown.size() < rows.rows().size())
        result.put("note", "仅展示前 " + shown.size() + " 行，完整结果已展示给用户");
      return write(result);
    }

    private static String write(Object value) {
      try {
        return JSON.writeValueAsString(value);
      } catch (Exception e) {
        throw new IllegalStateException("无法序列化工具结果", e);
      }
    }
  }
}
