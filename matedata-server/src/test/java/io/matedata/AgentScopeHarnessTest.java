package io.matedata;

import static io.matedata.FakeModelServer.*;
import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.matedata.harness.ExecutionStep;
import io.matedata.harness.QueryAgent;
import io.matedata.harness.infrastructure.AgentScopeQueryAgent;
import io.matedata.semantic.QueryPlan;
import io.matedata.semantic.SemanticModel;
import io.matedata.semantic.ValuesPlan;
import io.matedata.shared.ApplicationException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentScopeHarnessTest {
  @TempDir Path dir;

  static final QueryAgent.Rows REGION_ROWS =
      new QueryAgent.Rows(
          List.of("region", "revenue"),
          List.of(
              Map.of("region", "华东", "revenue", new BigDecimal("1449000.00")),
              Map.of("region", "西部", "revenue", new BigDecimal("1114200.00"))));

  /** describe_dataset → run_query → interpretation, as a well-behaved model. */
  static Script fullFlow(FakeModelServer[] self) {
    return (request, call) -> {
      int toolResults = toolResultsThisTurn(request);
      return switch (toolResults) {
        case 0 -> self[0].toolCall("call_d", "describe_dataset", Map.of());
        case 1 ->
            self[0].toolCall(
                "call_q",
                "run_query",
                Map.of(
                    "metric", "revenue", "dimension", "region", "filtersJson", "{}", "limit", 10));
        default -> text("华东销售额最高，为 1449000.00；西部最低。");
      };
    };
  }

  FakeModelServer server(Protocol protocol) throws Exception {
    var holder = new FakeModelServer[1];
    holder[0] = new FakeModelServer(protocol, fullFlow(holder));
    return holder[0];
  }

  QueryAgent.Turn turn(String question, String session) {
    return new QueryAgent.Turn(
        question, SemanticModel.sales(), "alice", UUID.randomUUID().toString(), session);
  }

  @Test
  void agentReadsVocabularyRunsOneGovernedQueryAndInterpretsTheRows() throws Exception {
    try (var server = server(Protocol.OPENAI_COMPATIBLE)) {
      var settings = server.settings(dir, 6, 15);
      var agent = new AgentScopeQueryAgent(settings, dir.toString());
      var plans = new CopyOnWriteArrayList<QueryPlan>();
      var steps = new CopyOnWriteArrayList<ExecutionStep>();
      var reply =
          agent.answer(
              turn("各区域销售额", "session-one"),
              plan -> {
                plans.add(plan);
                return REGION_ROWS;
              },
              steps::add);

      assertThat(reply.answer()).isEqualTo("华东销售额最高，为 1449000.00；西部最低。");
      assertThat(plans).containsExactly(new QueryPlan("revenue", "region", Map.of(), 10));
      for (JsonNode request : server.requests) {
        var names = new ArrayList<String>();
        request
            .path("tools")
            .forEach(tool -> names.add(tool.path("function").path("name").asText()));
        assertThat(names)
            .containsExactlyInAnyOrder("describe_dataset", "list_dimension_values", "run_query");
      }
      // The model sees business vocabulary and governed rows, never physical names or secrets.
      String sent = server.requests.toString();
      assertThat(sent).contains("销售额", "区域", "1449000.00");
      assertThat(sent).doesNotContain("amount", "sales_month", "demo_sales", "private-test-key");
      assertThat(settings.view().toString()).doesNotContain("private-test-key");
      assertThat(steps).extracting(ExecutionStep::name).contains("模型调用 #1", "语义工具", "Harness 结束");
      assertThat(steps.getLast().status()).isEqualTo("SUCCEEDED");
      assertThat(steps.toString()).doesNotContain("private-test-key", "各区域销售额");
    }
  }

  @Test
  void followUpsShareMemoryOnlyWithinTheSameSessionKey() throws Exception {
    try (var server = server(Protocol.OPENAI_COMPATIBLE)) {
      var agent = new AgentScopeQueryAgent(server.settings(dir, 6, 15), dir.toString());
      QueryAgent.GovernedQuery rows = plan -> REGION_ROWS;
      agent.answer(turn("各区域销售额", "conversation-a"), rows, step -> {});
      int firstTurn = server.requests.size();
      agent.answer(turn("那西部呢", "conversation-a"), rows, step -> {});
      var followUp = server.requests.get(firstTurn);
      assertThat(messages(followUp, "user"))
          .extracting(FakeModelServer::content)
          .contains("各区域销售额", "那西部呢");

      int beforeOther = server.requests.size();
      agent.answer(turn("各区域销售额", "conversation-b"), rows, step -> {});
      assertThat(messages(server.requests.get(beforeOther), "user"))
          .extracting(FakeModelServer::content)
          .containsExactly("各区域销售额");
    }
  }

  @Test
  void conversationsAreBoundedToAFixedNumberOfTurns() throws Exception {
    try (var server = server(Protocol.OPENAI_COMPATIBLE)) {
      var agent = new AgentScopeQueryAgent(server.settings(dir, 6, 15), dir.toString());
      for (int i = 0; i < 8; i++)
        agent.answer(turn("各区域销售额 " + i, "long-conversation"), plan -> REGION_ROWS, step -> {});
      int calls = server.requests.size();
      assertThatThrownBy(
              () ->
                  agent.answer(turn("再看一次", "long-conversation"), plan -> REGION_ROWS, step -> {}))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("新建对话");
      assertThat(server.requests).hasSize(calls);
    }
  }

  @Test
  void revokedScopeDuringTheQueryFailsTheRunAndNeverReachesTheModel() throws Exception {
    try (var server = server(Protocol.OPENAI_COMPATIBLE)) {
      var agent = new AgentScopeQueryAgent(server.settings(dir, 6, 15), dir.toString());
      assertThatThrownBy(
              () ->
                  agent.answer(
                      turn("各区域销售额", "revoked"),
                      plan -> {
                        throw new ApplicationException(
                            ApplicationException.Kind.FORBIDDEN, "数据权限或语义模型已变更，请重新查询");
                      },
                      step -> {}))
          .isInstanceOfSatisfying(
              ApplicationException.class,
              e -> assertThat(e.kind()).isEqualTo(ApplicationException.Kind.FORBIDDEN));
      assertThat(server.requests.toString()).doesNotContain("1449000");
    }
  }

  @Test
  void answersArePlainTextForTheUi() {
    assertThat(
            AgentScopeQueryAgent.plain(
                "## 结论\n\n\n\n**华东**最高：\n*   **华东**：1,449,000.00\n- 西部：1,114,200.00"))
        .isEqualTo("结论\n\n华东最高：\n- 华东：1,449,000.00\n- 西部：1,114,200.00");
  }

  @Test
  void aClarifyingReplyIsReturnedAsTextWithoutTouchingData() throws Exception {
    try (var server =
        new FakeModelServer(Protocol.OPENAI_COMPATIBLE, (request, call) -> text("你想按哪个维度查看利润？"))) {
      var agent = new AgentScopeQueryAgent(server.settings(dir, 6, 15), dir.toString());
      var steps = new CopyOnWriteArrayList<ExecutionStep>();
      var reply =
          agent.answer(
              turn("利润", "clarify"),
              plan -> {
                throw new AssertionError("governed query must not run");
              },
              steps::add);
      assertThat(reply.answer()).isEqualTo("你想按哪个维度查看利润？");
      assertThat(steps.getLast().detail()).contains("未执行查询");
    }
  }

  /** Scripted model: the given run_query arguments on the first call, then a text reply. */
  FakeModelServer queryThen(Map<String, Object> arguments, String answer) throws Exception {
    var holder = new FakeModelServer[1];
    holder[0] =
        new FakeModelServer(
            Protocol.OPENAI_COMPATIBLE,
            (request, call) ->
                toolResultsThisTurn(request) == 0
                    ? holder[0].toolCall("q", "run_query", arguments)
                    : text(answer));
    return holder[0];
  }

  static Map<String, Object> query(String dimension, String sort) {
    var arguments = new LinkedHashMap<String, Object>();
    arguments.put("metric", "revenue");
    arguments.put("dimension", dimension);
    arguments.put("filtersJson", "{}");
    arguments.put("limit", 2);
    if (sort != null) arguments.put("sort", sort);
    return arguments;
  }

  @Test
  void anExhaustedBudgetAfterTheQueryKeepsTheRowsAndNeverShowsFrameworkErrors() throws Exception {
    var holder = new FakeModelServer[1];
    holder[0] =
        new FakeModelServer(
            Protocol.OPENAI_COMPATIBLE,
            (request, call) ->
                call == 1
                    ? holder[0].toolCall("q", "run_query", query("region", null))
                    : holder[0].toolCall("m", "describe_dataset", Map.of()));
    try (var server = holder[0]) {
      var agent = new AgentScopeQueryAgent(server.settings(dir, 3, 15), dir.toString());
      var plans = new CopyOnWriteArrayList<QueryPlan>();
      var steps = new CopyOnWriteArrayList<ExecutionStep>();
      var reply =
          agent.answer(
              turn("销售额最高的两个区域", "exhausted"),
              plan -> {
                plans.add(plan);
                return REGION_ROWS;
              },
              steps::add);
      assertThat(reply.answer()).isEmpty();
      assertThat(plans).hasSize(1);
      assertThat(steps.getLast().status()).isEqualTo("SUCCEEDED");
      assertThat(steps.getLast().detail()).contains("平台摘要");
      assertThat(steps.toString()).doesNotContain("Maximum iterations");
    }
  }

  @Test
  void aTimeoutWhileInterpretingKeepsTheCompletedQuery() throws Exception {
    var holder = new FakeModelServer[1];
    holder[0] =
        new FakeModelServer(
            Protocol.OPENAI_COMPATIBLE,
            (request, call) -> {
              if (call == 1) return holder[0].toolCall("q", "run_query", query("region", null));
              Thread.sleep(10_000);
              return text("too late");
            });
    try (var server = holder[0]) {
      var agent = new AgentScopeQueryAgent(server.settings(dir, 6, 5), dir.toString());
      var plans = new CopyOnWriteArrayList<QueryPlan>();
      var reply =
          org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
              java.time.Duration.ofSeconds(9),
              () ->
                  agent.answer(
                      turn("各区域销售额", "slow-interpretation"),
                      plan -> {
                        plans.add(plan);
                        return REGION_ROWS;
                      },
                      step -> {}));
      assertThat(reply.answer()).isEmpty();
      assertThat(plans).hasSize(1);
    }
  }

  @Test
  void lowestAndMostRecentQuestionsReachTheCompilerAsExplicitSorts() throws Exception {
    try (var server = queryThen(query("region", "metric_asc"), "西部最低")) {
      var agent = new AgentScopeQueryAgent(server.settings(dir, 6, 15), dir.toString());
      var plans = new CopyOnWriteArrayList<QueryPlan>();
      agent.answer(
          turn("销售额最低的两个区域", "lowest"),
          plan -> {
            plans.add(plan);
            return REGION_ROWS;
          },
          step -> {});
      assertThat(plans)
          .containsExactly(
              new QueryPlan("revenue", "region", Map.of(), 2, QueryPlan.Sort.METRIC_ASC));
      assertThat(server.requests.getFirst().toString()).contains("metric_asc", "dimension_desc");
    }
    try (var server = queryThen(query("region", "cheapest_first"), "无法回答")) {
      var agent = new AgentScopeQueryAgent(server.settings(dir, 6, 15), dir.toString());
      assertThatThrownBy(
              () ->
                  agent.answer(
                      turn("销售额最低的两个区域", "invalid-sort"),
                      plan -> {
                        throw new AssertionError(
                            "an invalid sort must be rejected before data access");
                      },
                      step -> {}))
          .isInstanceOf(IllegalArgumentException.class);
      assertThat(server.requests.get(1).toString()).contains("查询被拒绝", "sort 只能是");
    }
  }

  @Test
  void rowScopedResultsTellTheModelWithoutRevealingTheFilter() throws Exception {
    for (boolean scoped : List.of(true, false)) {
      try (var server = queryThen(query("region", null), "华东销售额为 1449000.00")) {
        var agent = new AgentScopeQueryAgent(server.settings(dir, 6, 15), dir.toString());
        agent.answer(
            turn("各区域销售额", "scoped-" + scoped),
            plan ->
                new QueryAgent.Rows(
                    List.of("region", "revenue"),
                    List.of(Map.of("region", "华东", "revenue", new BigDecimal("1449000.00"))),
                    scoped),
            step -> {});
        var toolResult = messages(server.requests.get(1), "tool").getLast().toString();
        if (scoped) assertThat(toolResult).contains("数据权限", "不代表全量");
        else assertThat(toolResult).doesNotContain("数据权限");
      }
    }
  }

  @Test
  void onlyTheMostRecentQuestionsSendTheirToolResultsToTheModel() throws Exception {
    try (var server = server(Protocol.OPENAI_COMPATIBLE)) {
      var agent = new AgentScopeQueryAgent(server.settings(dir, 6, 15), dir.toString());
      for (int turn = 1; turn <= 3; turn++) {
        String marker = "turn-" + turn + "-value";
        int before = server.requests.size();
        agent.answer(
            turn("各区域销售额 " + turn, "trimmed"),
            plan -> new QueryAgent.Rows(List.of("region"), List.of(Map.of("region", marker))),
            step -> {});
        if (turn == 3) {
          String firstRequest = server.requests.get(before).toString();
          assertThat(firstRequest).doesNotContain("turn-1-value").contains("turn-2-value");
          assertThat(firstRequest).contains("较早轮次的工具结果已省略");
          // Questions and answers from every earlier turn stay in context.
          assertThat(firstRequest).contains("各区域销售额 1", "各区域销售额 2");
        }
      }
    }
  }

  @Test
  void trimmingReplacesOnlyOlderToolResults() {
    var messages = new ArrayList<io.agentscope.core.message.Msg>();
    for (int turn = 1; turn <= 3; turn++) {
      messages.add(
          io.agentscope.core.message.Msg.builder()
              .role(io.agentscope.core.message.MsgRole.USER)
              .textContent("q" + turn)
              .build());
      messages.add(
          io.agentscope.core.message.Msg.builder()
              .role(io.agentscope.core.message.MsgRole.TOOL)
              .content(
                  new io.agentscope.core.message.ToolResultBlock(
                      "call-" + turn,
                      "run_query",
                      io.agentscope.core.message.TextBlock.builder().text("rows-" + turn).build()))
              .build());
    }
    var trimmed = AgentScopeQueryAgent.forModel(messages);
    assertThat(trimmed).hasSize(messages.size());
    var visible = new StringBuilder();
    for (var message : trimmed) {
      message
          .getContentBlocks(io.agentscope.core.message.TextBlock.class)
          .forEach(block -> visible.append(block.getText()).append('|'));
      for (var result : message.getContentBlocks(io.agentscope.core.message.ToolResultBlock.class))
        for (var output : result.getOutput())
          if (output instanceof io.agentscope.core.message.TextBlock text)
            visible.append(text.getText()).append('|');
    }
    assertThat(visible.toString())
        .doesNotContain("rows-1")
        .contains("rows-2", "rows-3", "q1", "较早轮次的工具结果已省略");
    var older =
        trimmed
            .get(1)
            .getContentBlocks(io.agentscope.core.message.ToolResultBlock.class)
            .getFirst();
    assertThat(older.getId()).isEqualTo("call-1");
    var twoQuestions = messages.subList(0, 4);
    assertThat(AgentScopeQueryAgent.forModel(twoQuestions)).isSameAs(twoQuestions);
  }

  /** A governed query that answers lookups from a fixed list and records what was asked. */
  static final class RecordingQuery implements QueryAgent.GovernedQuery {
    final List<ValuesPlan> lookups = new CopyOnWriteArrayList<>();
    final List<QueryPlan> plans = new CopyOnWriteArrayList<>();
    final List<String> values;
    final boolean scoped;

    RecordingQuery(List<String> values, boolean scoped) {
      this.values = values;
      this.scoped = scoped;
    }

    @Override
    public QueryAgent.Rows run(QueryPlan plan) {
      plans.add(plan);
      return REGION_ROWS;
    }

    @Override
    public QueryAgent.Values values(ValuesPlan plan) {
      lookups.add(plan);
      return new QueryAgent.Values(values, false, scoped);
    }
  }

  /** Looks up region values for "华东", then filters on the value the lookup returned. */
  FakeModelServer groundedFilter(Map<String, Object> lookup) throws Exception {
    var holder = new FakeModelServer[1];
    holder[0] =
        new FakeModelServer(
            Protocol.OPENAI_COMPATIBLE,
            (request, call) ->
                switch (toolResultsThisTurn(request)) {
                  case 0 -> holder[0].toolCall("v", "list_dimension_values", lookup);
                  case 1 ->
                      holder[0].toolCall(
                          "q",
                          "run_query",
                          Map.of(
                              "metric",
                              "revenue",
                              "dimension",
                              "",
                              "filtersJson",
                              "{\"region\":\"华东\"}",
                              "limit",
                              10));
                  default -> text("华东销售额为 1449000.00");
                });
    return holder[0];
  }

  @Test
  void theAgentGroundsFiltersInLookedUpDimensionValues() throws Exception {
    try (var server = groundedFilter(Map.of("dimension", "region", "keyword", "华东"))) {
      var agent = new AgentScopeQueryAgent(server.settings(dir, 6, 15), dir.toString());
      var query = new RecordingQuery(List.of("华东", "华东区"), true);
      var steps = new CopyOnWriteArrayList<ExecutionStep>();
      var reply = agent.answer(turn("华东区的销售额", "grounded"), query, steps::add);
      assertThat(reply.answer()).contains("华东");
      assertThat(query.lookups).containsExactly(new ValuesPlan("region", "华东", Map.of(), 20));
      assertThat(query.plans)
          .containsExactly(new QueryPlan("revenue", "", Map.of("region", "华东"), 10));
      var lookupResult = messages(server.requests.get(1), "tool").getLast().toString();
      assertThat(lookupResult).contains("华东区", "数据权限");
    }
  }

  @Test
  void invalidOrUnsupportedLookupsNeverTouchData() throws Exception {
    for (var lookup :
        List.of(
            Map.<String, Object>of("dimension", "secret_dimension"),
            Map.<String, Object>of("dimension", "region", "limit", 51))) {
      try (var server = groundedFilter(lookup)) {
        var agent = new AgentScopeQueryAgent(server.settings(dir, 6, 15), dir.toString());
        var query = new RecordingQuery(List.of("华东"), false);
        agent.answer(turn("华东区的销售额", "rejected-" + lookup.size()), query, step -> {});
        assertThat(query.lookups).isEmpty();
        assertThat(server.requests.get(1).toString()).contains("维度值查询被拒绝");
        // The question itself can still be answered.
        assertThat(query.plans).hasSize(1);
      }
    }
    // A caller without lookup support fails the lookup, not the question.
    try (var server = groundedFilter(Map.of("dimension", "region"))) {
      var agent = new AgentScopeQueryAgent(server.settings(dir, 6, 15), dir.toString());
      var plans = new CopyOnWriteArrayList<QueryPlan>();
      agent.answer(
          turn("华东区的销售额", "unsupported"),
          plan -> {
            plans.add(plan);
            return REGION_ROWS;
          },
          step -> {});
      assertThat(server.requests.get(1).toString()).contains("维度值查询失败");
      assertThat(plans).hasSize(1);
    }
  }

  @Test
  void anEmptyLookupTellsTheModelTheValueDoesNotExist() throws Exception {
    try (var server = groundedFilter(Map.of("dimension", "region", "keyword", "东北"))) {
      var agent = new AgentScopeQueryAgent(server.settings(dir, 6, 15), dir.toString());
      agent.answer(turn("东北的销售额", "missing"), new RecordingQuery(List.of(), false), step -> {});
      assertThat(messages(server.requests.get(1), "tool").getLast().toString())
          .contains("没有匹配的取值")
          .doesNotContain("数据权限");
    }
  }

  @Test
  void aRevokedScopeDuringALookupStopsTheQuestion() throws Exception {
    try (var server = groundedFilter(Map.of("dimension", "region"))) {
      var agent = new AgentScopeQueryAgent(server.settings(dir, 6, 15), dir.toString());
      var plans = new CopyOnWriteArrayList<QueryPlan>();
      var revoked =
          new QueryAgent.GovernedQuery() {
            @Override
            public QueryAgent.Rows run(QueryPlan plan) {
              plans.add(plan);
              return REGION_ROWS;
            }

            @Override
            public QueryAgent.Values values(ValuesPlan plan) {
              throw new ApplicationException(
                  ApplicationException.Kind.FORBIDDEN, "数据权限或语义模型已变更，请重新查询");
            }
          };
      assertThatThrownBy(() -> agent.answer(turn("华东区的销售额", "revoked-lookup"), revoked, step -> {}))
          .isInstanceOf(ApplicationException.class);
      assertThat(plans).isEmpty();
    }
  }

  @Test
  void overEscapedFiltersAreAcceptedAndMalformedOnesExplainThemselves() {
    assertThat(AgentScopeQueryAgent.filters("{\\\"region\\\":\\\"华东\\\"}"))
        .containsExactly(Map.entry("region", "华东"));
    assertThat(AgentScopeQueryAgent.filters("{\"region\":\"华东\"}"))
        .containsExactly(Map.entry("region", "华东"));
    assertThat(AgentScopeQueryAgent.filters(" ")).isEmpty();
    for (String malformed : List.of("region=华东", "[\"华东\"]", "{\"region\":null}"))
      assertThatThrownBy(() -> AgentScopeQueryAgent.filters(malformed))
          .as(malformed)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("filtersJson");
  }

  @Test
  void aFailedQueryAttemptCanNeverEndInAnInventedAnswer() throws Exception {
    var holder = new FakeModelServer[1];
    holder[0] =
        new FakeModelServer(
            Protocol.OPENAI_COMPATIBLE,
            (request, call) ->
                toolResultsThisTurn(request) == 0
                    ? holder[0].toolCall(
                        "q",
                        "run_query",
                        Map.of(
                            "metric", "gross_margin",
                            "dimension", "region",
                            "filtersJson", "{}",
                            "limit", 10))
                    : text("华东区的销售额是 12000000。"));
    try (var server = holder[0]) {
      var agent = new AgentScopeQueryAgent(server.settings(dir, 6, 15), dir.toString());
      assertThatThrownBy(
              () ->
                  agent.answer(
                      turn("华东区的毛利", "invented"),
                      plan -> {
                        throw new AssertionError("an unknown metric must not reach data");
                      },
                      step -> {}))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("已拦截")
          .hasMessageNotContaining("12000000");
      // The model was told exactly what was wrong.
      assertThat(server.requests.get(1).toString()).contains("未知指标：gross_margin");
    }
  }

  @Test
  void numbersWithoutAnyQueryAreBlockedButQuestionsPass() throws Exception {
    try (var server =
        new FakeModelServer(
            Protocol.OPENAI_COMPATIBLE, (request, call) -> text("华东销售额约为 1200 万。"))) {
      var agent = new AgentScopeQueryAgent(server.settings(dir, 6, 15), dir.toString());
      assertThatThrownBy(
              () ->
                  agent.answer(
                      turn("华东销售额", "no-query-number"),
                      plan -> {
                        throw new AssertionError("not called");
                      },
                      step -> {}))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("已拦截");
    }
  }

  @Test
  void ollamaProviderUsesTheNativeProtocolWithoutAKey() throws Exception {
    try (var server = server(Protocol.OLLAMA)) {
      var settings = server.settings(dir, 6, 15);
      assertThat(settings.configured()).isTrue();
      var agent = new AgentScopeQueryAgent(settings, dir.toString());
      var plans = new CopyOnWriteArrayList<QueryPlan>();
      var reply =
          agent.answer(
              turn("各区域销售额", "ollama"),
              plan -> {
                plans.add(plan);
                return REGION_ROWS;
              },
              step -> {});
      assertThat(reply.answer()).contains("华东");
      assertThat(plans).containsExactly(new QueryPlan("revenue", "region", Map.of(), 10));
      var first = server.requests.getFirst();
      assertThat(first.path("model").asText()).isEqualTo("test-model");
      assertThat(first.path("think").asBoolean(true)).isFalse();
      assertThat(first.path("tools").size()).isEqualTo(3);
    }
  }
}
