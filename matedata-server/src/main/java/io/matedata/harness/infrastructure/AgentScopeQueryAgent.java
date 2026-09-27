package io.matedata.harness.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.*;
import io.agentscope.core.message.ContentBlock;
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
  static final List<String> TOOL_NAMES =
      List.of("describe_dataset", "list_dimension_values", "run_query");

  /** Questions whose tool results the model still sees verbatim, including the current one. */
  static final int RECENT_TURNS = 2;

  static final String TRIMMED_RESULT = "较早轮次的工具结果已省略；如需这些数据，请重新调用 run_query。";

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
                  return next.apply(
                      new ModelCallInput(
                          forModel(input.messages()),
                          input.tools(),
                          input.options(),
                          input.model()));
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
        // Without governed rows, any conclusion is invented: only questions and refusals pass.
        if (tools.attempted())
          throw new IllegalArgumentException("智能体生成的查询未通过平台校验，已拦截其回答；请换个说法或明确指标、维度后重试");
        if (reply.get().chars().anyMatch(Character::isDigit))
          throw new IllegalArgumentException("智能体未执行查询却给出了数值，已拦截该回答；请明确指标和维度后重试");
        // A clarifying question or a refusal: returned as text, never as data.
        observer.accept(
            new ExecutionStep(
                "Harness 结束",
                "SUCCEEDED",
                "已完成 " + modelCalls.get() + " 次模型调用；智能体需要补充信息，未执行查询",
                elapsed(started)));
        return new Reply(reply.get());
      }
      // After an exhausted budget the final text is the framework's own error, never an answer.
      boolean interpreted = !exhausted.get() && !reply.get().isBlank();
      observer.accept(
          new ExecutionStep(
              "Harness 结束",
              "SUCCEEDED",
              "已完成 "
                  + modelCalls.get()
                  + " 次模型调用"
                  + (interpreted
                      ? "；已生成结果解读"
                      : exhausted.get() ? "；解读阶段达到执行步数上限，使用平台摘要" : "；模型未给出解读，使用平台摘要"),
              elapsed(started)));
      return new Reply(interpreted ? reply.get() : "");
    } catch (Exception e) {
      if (tools.fatal() == null && tools.executed()) {
        // The governed query already produced authoritative rows; only the interpretation failed.
        observer.accept(
            new ExecutionStep(
                "Harness 结束",
                "SUCCEEDED",
                "查询已完成；解读阶段" + (exhausted.get() ? "达到执行步数上限" : "超时或模型调用失败") + "，使用平台摘要",
                elapsed(started)));
        return new Reply("");
      }
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
                (int) state.getContext().stream().filter(AgentScopeQueryAgent::isQuestion).count())
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
        + "1. 先调用 describe_dataset，了解当前用户已授权的指标与维度；只能使用返回的 id。\n"
        + "2. 用户提到具体的筛选值（如地区、品类、渠道名称），而你不确定数据中的准确写法时，"
        + "先调用 list_dimension_values 查询实际取值，再用准确的取值筛选；取值不存在时如实告诉用户，不要改用相近的值。\n"
        + "3. 调用 run_query 执行一次受治理查询：选择一个指标，至多一个分组维度；过滤条件只支持维度的等值筛选。"
        + "问“最低、最少、倒数”时 sort 用 metric_asc，问“最近 N 期”时 sort 用 dimension_desc，其余情况留空。"
        + "SQL 由平台生成并校验，你不能也不需要编写 SQL。\n"
        + "4. 根据 run_query 返回的数据，用简洁的中文回答问题：给出结论，引用具体数值，指出最高、最低或明显差异。"
        + "不得编造数据中没有的数值或原因。结果若注明已按数据权限限定，要说明结论只针对用户有权查看的范围，不要推断范围外的数据。"
        + "使用普通文本和换行，不要使用 Markdown 格式。\n"
        + "5. 追问时结合之前的对话：省略的指标、维度和筛选条件沿用上一轮查询，只替换用户提到的部分，然后重新调用 run_query。"
        + "例如上一轮按区域查销售额，追问“那利润呢”就按区域查利润。\n"
        + "6. 只有在确实无法判断用户意图时，才用一句话向用户确认，此时不要调用 run_query。"
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

  static QueryPlan.Sort sort(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return QueryPlan.Sort.valueOf(value.strip().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "sort 只能是 metric_desc、metric_asc、dimension_asc 或 dimension_desc，或留空");
    }
  }

  private static final ObjectMapper FILTERS = new ObjectMapper();

  /**
   * Parses the equality filters. Models often escape the quotes of this string argument once more
   * than needed ({@code {\"region\":\"华东\"}}); one extra layer is tolerated.
   */
  public static Map<String, String> filters(String json) {
    if (json == null || json.isBlank()) return Map.of();
    if (json.length() > 2000) throw new IllegalArgumentException("过滤条件过长");
    var type = new TypeReference<Map<String, String>>() {};
    for (String candidate : List.of(json, json.replace("\\\"", "\""))) {
      try {
        var filters = FILTERS.readValue(candidate, type);
        if (filters == null || filters.containsValue(null)) break;
        return filters;
      } catch (com.fasterxml.jackson.core.JacksonException e) {
        // Try the next candidate.
      }
    }
    throw new IllegalArgumentException("filtersJson 必须是维度 id 到取值的 JSON 对象，例如 {\"region\":\"华东\"}");
  }

  /**
   * Bounds the context sent to the model: tool results from questions before the most recent {@link
   * #RECENT_TURNS} are replaced by a short note. Stored memory is left unchanged.
   */
  public static List<Msg> forModel(List<Msg> messages) {
    var questions = new ArrayList<Integer>();
    for (int i = 0; i < messages.size(); i++) if (isQuestion(messages.get(i))) questions.add(i);
    if (questions.size() <= RECENT_TURNS) return messages;
    int keepFrom = questions.get(questions.size() - RECENT_TURNS);
    var trimmed = new ArrayList<Msg>(messages.size());
    for (int i = 0; i < messages.size(); i++) {
      var message = messages.get(i);
      if (i >= keepFrom || message.getContentBlocks(ToolResultBlock.class).isEmpty()) {
        trimmed.add(message);
        continue;
      }
      var content = new ArrayList<ContentBlock>();
      for (var block : message.getContent())
        content.add(
            block instanceof ToolResultBlock result
                ? new ToolResultBlock(
                    result.getId(),
                    result.getName(),
                    List.of(TextBlock.builder().text(TRIMMED_RESULT).build()),
                    result.getMetadata(),
                    result.getState())
                : block);
      trimmed.add(message.withContent(content));
    }
    return trimmed;
  }

  private static boolean isQuestion(Msg message) {
    return message.getRole() == MsgRole.USER
        && !message.getContentBlocks(TextBlock.class).isEmpty()
        && message.getContentBlocks(ToolResultBlock.class).isEmpty();
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
    private final AtomicBoolean attempted = new AtomicBoolean();
    private final AtomicReference<RuntimeException> fatal = new AtomicReference<>();

    SemanticTools(SemanticModel model, GovernedQuery query, Consumer<ExecutionStep> observer) {
      this.model = model;
      this.query = query;
      this.observer = observer;
    }

    /** The model called run_query at least once, whether or not it succeeded. */
    boolean attempted() {
      return attempted.get();
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

    @Tool(
        name = "describe_dataset",
        description = "列出当前用户在本数据集中已授权的指标（id、名称、别名、聚合方式）和维度（id、名称、别名、取值类型，日期使用 ISO 格式）。")
    public String describeDataset() {
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
              "describe_dataset：" + metrics.size() + " 个已授权指标，" + dimensions.size() + " 个已授权维度",
              elapsed(start)));
      var result = new LinkedHashMap<String, Object>();
      result.put("dataset", model.name());
      result.put("metrics", metrics);
      result.put("dimensions", dimensions);
      return write(result);
    }

    @Tool(
        name = "list_dimension_values",
        description =
            "查询某个已授权维度在数据中实际存在的取值（升序，最多 50 个），用于确认筛选值的准确写法。"
                + "keyword 可选，只对文本维度按包含关系匹配。每个问题最多调用 3 次。")
    public String listDimensionValues(
        @ToolParam(name = "dimension", description = "维度 id") String dimension,
        @ToolParam(name = "keyword", description = "可选，取值包含的文字，例如“华东”", required = false)
            String keyword,
        @ToolParam(name = "limit", description = "可选，返回数量 1–50，默认 20", required = false)
            Integer limit) {
      long start = System.nanoTime();
      if (fatal.get() != null) return "查询已终止：数据权限或语义模型已变更。请停止调用工具。";
      ValuesPlan plan;
      try {
        model.dimension(dimension);
        plan = new ValuesPlan(dimension, keyword, Map.of(), limit == null ? 20 : limit);
        // Validate against the permitted vocabulary before touching any data.
        new SemanticCompiler().compileValues(model, plan);
      } catch (Exception e) {
        observer.accept(
            new ExecutionStep("语义工具", "FAILED", "list_dimension_values：请求被校验器拒绝", elapsed(start)));
        return "维度值查询被拒绝：请使用 describe_dataset 返回的维度 id；keyword 只适用于文本维度，limit 为 1–50。";
      }
      try {
        var found = query.values(plan);
        var result = new LinkedHashMap<String, Object>();
        result.put("dimension", dimension);
        result.put("values", found.values());
        if (found.values().isEmpty()) result.put("note", "没有匹配的取值，请如实告诉用户数据中不存在该取值");
        else if (found.truncated())
          result.put("note", "取值较多，仅返回前 " + found.values().size() + " 个，可用 keyword 缩小范围");
        if (found.rowScoped()) result.put("scope", "取值已按当前用户的数据权限做行级限定");
        return write(result);
      } catch (ApplicationException e) {
        if (e.kind() == ApplicationException.Kind.FORBIDDEN) {
          fatal.compareAndSet(null, e);
          return "查询已终止：" + e.getMessage();
        }
        return "维度值查询失败：" + e.getMessage();
      } catch (IllegalArgumentException e) {
        return "维度值查询失败：" + e.getMessage();
      } catch (RuntimeException e) {
        // A failed lookup only loses grounding; the question can still be answered.
        observer.accept(
            new ExecutionStep("维度值查询", "FAILED", "list_dimension_values：数据源查询失败", elapsed(start)));
        return "维度值查询失败，请直接使用用户问题中的写法筛选。";
      }
    }

    @Tool(
        name = "run_query",
        description =
            "执行一次受治理的聚合查询并返回结果行。metric 为指标 id；dimension 为分组维度 id，不分组时传空字符串；"
                + "filtersJson 为维度 id 到等值筛选值的 JSON 对象，例如 {} 或 {\"region\":\"华东\"}；limit 为 1–1000；"
                + "sort 决定截取前 limit 行的顺序，可留空。每个问题只能成功执行一次。")
    public String runQuery(
        @ToolParam(name = "metric", description = "指标 id") String metric,
        @ToolParam(name = "dimension", description = "分组维度 id；总计时传空字符串") String dimension,
        @ToolParam(name = "filtersJson", description = "JSON 对象，例如 {} 或 {\"region\":\"华东\"}")
            String filtersJson,
        @ToolParam(name = "limit", description = "结果上限 1–1000") int limit,
        @ToolParam(
                name = "sort",
                description =
                    "可选。metric_desc 指标从高到低（默认）；metric_asc 从低到高，用于最低、最少；"
                        + "dimension_asc / dimension_desc 按维度值排序，时间维度默认从早到晚，最近 N 期用 dimension_desc",
                required = false)
            String sort) {
      long start = System.nanoTime();
      if (fatal.get() != null) return "查询已终止：数据权限或语义模型已变更。请停止调用工具。";
      if (executed.get()) return "本问题已完成查询，请直接根据已有结果回答，不要再次调用 run_query。";
      attempted.set(true);
      QueryPlan plan;
      try {
        plan = new QueryPlan(metric, dimension, filters(filtersJson), limit, sort(sort));
        // Validate against the permitted vocabulary before touching any data.
        new SqlGuard().verify(new SemanticCompiler().compile(model, plan), model, plan);
      } catch (Exception e) {
        observer.accept(new ExecutionStep("语义工具", "FAILED", "run_query：计划被校验器拒绝", elapsed(start)));
        // Compiler messages name only the model's own ids and values, so the model can
        // self-correct.
        return "查询被拒绝："
            + (e instanceof IllegalArgumentException && e.getMessage() != null
                ? e.getMessage()
                : "参数不完整或格式不正确")
            + "。请只使用 describe_dataset 返回的 id，修正后重新调用 run_query。";
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
      if (rows.rowScoped()) result.put("scope", "结果已按当前用户的数据权限做行级限定，只包含其有权查看的数据，不代表全量");
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
