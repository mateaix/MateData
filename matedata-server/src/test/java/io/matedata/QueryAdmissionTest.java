package io.matedata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.matedata.conversation.*;
import io.matedata.conversation.application.QueryService;
import io.matedata.harness.QueryAgent;
import io.matedata.identity.*;
import io.matedata.identity.application.DataAccessService;
import io.matedata.semantic.*;
import io.matedata.shared.ApplicationException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class QueryAdmissionTest {
  record Fixture(ModelRepository models, RunRepository runs, DataAccessService access) {}

  Fixture fixture(GrantRepository grants, String role) {
    var models = mock(ModelRepository.class);
    when(models.find("sales")).thenReturn(Optional.of(SemanticModel.sales()));
    var accounts = mock(AccountRepository.class);
    when(accounts.find("alice"))
        .thenReturn(Optional.of(new Account("alice", "Alice", role, "hash")));
    return new Fixture(
        models, mock(RunRepository.class), new DataAccessService(accounts, grants, models));
  }

  @Test
  void ninthConcurrentQueryIsRejectedAndSlotsAreReleasedAfterCompletion() throws Exception {
    var f = fixture(mock(GrantRepository.class), "ADMIN");
    var entered = new CountDownLatch(8);
    var release = new CountDownLatch(1);
    var agent = mock(QueryAgent.class);
    when(agent.answer(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              entered.countDown();
              if (!release.await(5, TimeUnit.SECONDS))
                throw new IllegalArgumentException("test deadline");
              invocation
                  .<QueryAgent.GovernedQuery>getArgument(1)
                  .run(new QueryPlan("revenue", "region", Map.of(), 10));
              return new QueryAgent.Reply("");
            });
    var executor = mock(QueryExecutor.class);
    when(executor.execute(any(), any(), any()))
        .thenReturn(
            new QueryExecutor.Result(
                List.of("region", "revenue"), List.of(Map.of("region", "华东", "revenue", 1))));
    var service = new QueryService(f.models(), f.runs(), agent, executor, f.access());
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = new ArrayList<Future<QueryRun>>();
      try {
        for (int i = 0; i < 8; i++)
          futures.add(pool.submit(() -> service.ask("alice", "各区域销售额", "sales", "agent", null)));
        assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> service.ask("alice", "各区域销售额", "sales", "agent", null))
            .isInstanceOfSatisfying(
                ApplicationException.class,
                error -> assertThat(error.kind()).isEqualTo(ApplicationException.Kind.BUSY));
      } finally {
        release.countDown();
      }
      for (var future : futures)
        assertThat(future.get(3, TimeUnit.SECONDS).status()).isEqualTo("SUCCEEDED");
      assertThat(service.ask("alice", "各区域销售额", "sales", "agent", null).status())
          .isEqualTo("SUCCEEDED");
      verify(f.runs(), times(9)).save(eq("alice"), any());
    }
  }

  @Test
  void revokingScopeDuringExecutionDiscardsTheResultBeforePersistence() throws Exception {
    var current =
        new AtomicReference<>(
            new DatasetGrant(
                "alice", "sales", true, List.of("revenue"), List.of("region"), Map.of()));
    var grants = mock(GrantRepository.class);
    when(grants.find("alice", "sales")).thenAnswer(invocation -> Optional.of(current.get()));
    var f = fixture(grants, "ANALYST");
    var executor = mock(QueryExecutor.class);
    when(executor.execute(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              current.set(
                  new DatasetGrant(
                      "alice", "sales", false, List.of("revenue"), List.of("region"), Map.of()));
              return new QueryExecutor.Result(
                  List.of("region", "revenue"),
                  List.of(Map.of("region", "restricted-result-value", "revenue", 99)));
            });
    var service =
        new QueryService(f.models(), f.runs(), mock(QueryAgent.class), executor, f.access());
    var result = service.ask("alice", "各区域销售额", "sales", "demo", null);
    assertThat(result.status()).isEqualTo("FAILED");
    assertThat(result.rows()).isEmpty();
    assertThat(result.toString()).doesNotContain("restricted-result-value");
    verify(f.runs()).save("alice", result);
  }

  @Test
  void rowFilterConflictReportsTheAuthorizationReason() throws Exception {
    var grants = mock(GrantRepository.class);
    when(grants.find("alice", "sales"))
        .thenReturn(
            Optional.of(
                new DatasetGrant(
                    "alice",
                    "sales",
                    true,
                    List.of("revenue"),
                    List.of("region"),
                    Map.of("region", "华东"))));
    var f = fixture(grants, "ANALYST");
    var agent = mock(QueryAgent.class);
    when(agent.answer(any(), any(), any()))
        .thenAnswer(
            invocation ->
                invocation
                    .<QueryAgent.GovernedQuery>getArgument(1)
                    .run(new QueryPlan("revenue", null, Map.of("region", "华南"), 10)));
    var executor = mock(QueryExecutor.class);
    var service = new QueryService(f.models(), f.runs(), agent, executor, f.access());
    var result = service.ask("alice", "华南销售额", "sales", "agent", null);
    assertThat(result.status()).isEqualTo("FAILED");
    assertThat(result.error()).isEqualTo("查询筛选与行级授权冲突");
    verify(executor, never()).execute(any(), any(), any());
  }

  QueryService agentService(Fixture f, QueryAgent agent) throws Exception {
    var executor = mock(QueryExecutor.class);
    when(executor.execute(any(), any(), any()))
        .thenReturn(
            new QueryExecutor.Result(
                List.of("region", "revenue"), List.of(Map.of("region", "华东", "revenue", 1))));
    return new QueryService(f.models(), f.runs(), agent, executor, f.access());
  }

  @Test
  void agentReplyBecomesTheAnswerAndRowsComeFromTheGovernedQuery() throws Exception {
    var f = fixture(mock(GrantRepository.class), "ADMIN");
    var agent = mock(QueryAgent.class);
    when(agent.answer(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              var rows =
                  invocation
                      .<QueryAgent.GovernedQuery>getArgument(1)
                      .run(new QueryPlan("revenue", "region", Map.of(), 10));
              assertThat(rows.rows()).hasSize(1);
              return new QueryAgent.Reply("华东最高");
            });
    var run = agentService(f, agent).ask("alice", "各区域销售额", "sales", "agent", null);
    assertThat(run.status()).isEqualTo("SUCCEEDED");
    assertThat(run.answer()).isEqualTo("华东最高");
    assertThat(run.sql()).contains("GROUP BY region");
    assertThat(run.rows()).hasSize(1);
  }

  @Test
  void agentThatNeverQueriesCannotProduceASuccess() throws Exception {
    var f = fixture(mock(GrantRepository.class), "ADMIN");
    var agent = mock(QueryAgent.class);
    when(agent.answer(any(), any(), any())).thenReturn(new QueryAgent.Reply("你想按哪个维度查看利润？"));
    var service = agentService(f, agent);
    var clarification = service.ask("alice", "利润", "sales", "agent", null);
    assertThat(clarification.status()).isEqualTo("NEEDS_INPUT");
    assertThat(clarification.answer()).isEqualTo("你想按哪个维度查看利润？");
    assertThat(clarification.rows()).isEmpty();
    assertThat(clarification.sql()).isEmpty();

    when(agent.answer(any(), any(), any())).thenReturn(new QueryAgent.Reply(""));
    var silent = service.ask("alice", "利润", "sales", "agent", null);
    assertThat(silent.status()).isEqualTo("FAILED");
    assertThat(silent.error()).contains("未执行查询");
  }

  @Test
  void followUpsKeepTheirSessionUntilTheAuthorizationScopeChanges() throws Exception {
    var current =
        new AtomicReference<>(
            new DatasetGrant(
                "alice", "sales", true, List.of("revenue"), List.of("region"), Map.of()));
    var grants = mock(GrantRepository.class);
    when(grants.find("alice", "sales")).thenAnswer(invocation -> Optional.of(current.get()));
    var f = fixture(grants, "ANALYST");
    var sessions = new ArrayList<String>();
    var agent = mock(QueryAgent.class);
    when(agent.answer(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              sessions.add(invocation.<QueryAgent.Turn>getArgument(0).sessionKey());
              invocation
                  .<QueryAgent.GovernedQuery>getArgument(1)
                  .run(new QueryPlan("revenue", "region", Map.of(), 10));
              return new QueryAgent.Reply("");
            });
    var service = agentService(f, agent);
    service.ask("alice", "各区域销售额", "sales", "agent", "conversation-1");
    service.ask("alice", "那华东呢", "sales", "agent", "conversation-1");
    service.ask("alice", "各区域销售额", "sales", "agent", "conversation-2");
    current.set(
        new DatasetGrant(
            "alice", "sales", true, List.of("revenue"), List.of("region"), Map.of("region", "华东")));
    service.ask("alice", "各区域销售额", "sales", "agent", "conversation-1");
    assertThat(sessions.get(1)).isEqualTo(sessions.get(0));
    assertThat(sessions.get(2)).isNotEqualTo(sessions.get(0));
    assertThat(sessions.get(3)).isNotEqualTo(sessions.get(0));
    assertThat(sessions).allMatch(key -> key.matches("c[0-9a-f]{32}"));
  }

  @Test
  void conversationIdsAreValidatedBeforeAnyWork() {
    var f = fixture(mock(GrantRepository.class), "ADMIN");
    var service =
        new QueryService(
            f.models(), f.runs(), mock(QueryAgent.class), mock(QueryExecutor.class), f.access());
    for (String id : List.of("../escape", "a".repeat(65), "", "with space"))
      assertThatThrownBy(() -> service.ask("alice", "各区域销售额", "sales", "demo", id))
          .as(id)
          .isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(f.runs());
  }

  @Test
  void sortSurvivesRowLevelConstraintsAndRowsReportTheirScope() throws Exception {
    var grants = mock(GrantRepository.class);
    when(grants.find("alice", "sales"))
        .thenReturn(
            Optional.of(
                new DatasetGrant(
                    "alice",
                    "sales",
                    true,
                    List.of("revenue"),
                    List.of("category"),
                    Map.of("region", "华东"))));
    var f = fixture(grants, "ANALYST");
    var scoped = new AtomicReference<Boolean>();
    var agent = mock(QueryAgent.class);
    when(agent.answer(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              var rows =
                  invocation
                      .<QueryAgent.GovernedQuery>getArgument(1)
                      .run(
                          new QueryPlan(
                              "revenue", "category", Map.of(), 2, QueryPlan.Sort.METRIC_ASC));
              scoped.set(rows.rowScoped());
              return new QueryAgent.Reply("");
            });
    var run = agentService(f, agent).ask("alice", "销售额最低的两个品类", "sales", "agent", null);
    assertThat(run.status()).isEqualTo("SUCCEEDED");
    assertThat(run.sql()).contains("WHERE region = ?").endsWith("ORDER BY 2 ASC LIMIT 2");
    assertThat(scoped.get()).isTrue();
    // A blank interpretation falls back to the platform summary.
    assertThat(run.answer()).startsWith("已基于「销售经营分析」计算销售额");

    var unrestricted = fixture(mock(GrantRepository.class), "ADMIN");
    agentService(unrestricted, agent).ask("alice", "销售额最低的两个品类", "sales", "agent", null);
    assertThat(scoped.get()).isFalse();
  }

  @Test
  void valueLookupsAreGrantedScopedVerifiedAndBounded() throws Exception {
    var grants = mock(GrantRepository.class);
    when(grants.find("alice", "sales"))
        .thenReturn(
            Optional.of(
                new DatasetGrant(
                    "alice",
                    "sales",
                    true,
                    List.of("revenue"),
                    List.of("category"),
                    Map.of("region", "华东"))));
    var f = fixture(grants, "ANALYST");
    var executor = mock(QueryExecutor.class);
    var lookedUp = new ArrayList<ValuesPlan>();
    when(executor.values(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              lookedUp.add(invocation.getArgument(1));
              CompiledQuery compiled = invocation.getArgument(2);
              assertThat(compiled.sql()).contains("region = ?", "LIKE ? ESCAPE '!'");
              return new QueryExecutor.Result(
                  List.of("category"),
                  List.of(
                      Map.of("category", "专业咨询"),
                      Map.of("category", "智能硬件"),
                      Map.of("category", "软件服务")));
            });
    var outcomes = new ArrayList<Object>();
    var agent = mock(QueryAgent.class);
    when(agent.answer(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              QueryAgent.GovernedQuery query = invocation.getArgument(1);
              var found = query.values(new ValuesPlan("category", "咨询", Map.of("region", "华南"), 2));
              outcomes.add(found);
              for (var dimension : List.of("region", "category", "category"))
                try {
                  outcomes.add(query.values(new ValuesPlan(dimension, "咨询", Map.of(), 2)));
                } catch (RuntimeException e) {
                  outcomes.add(e.getMessage());
                }
              return new QueryAgent.Reply("");
            });
    var run =
        new QueryService(f.models(), f.runs(), agent, executor, f.access())
            .ask("alice", "咨询类销售额", "sales", "agent", null);

    // Requested filters are ignored; the user's row filter is always applied.
    assertThat(lookedUp.getFirst())
        .isEqualTo(new ValuesPlan("category", "咨询", Map.of("region", "华东"), 2));
    var first = (QueryAgent.Values) outcomes.getFirst();
    assertThat(first.values()).containsExactly("专业咨询", "智能硬件");
    assertThat(first.truncated()).isTrue();
    assertThat(first.rowScoped()).isTrue();
    // region is not a granted dimension; the fourth lookup exceeds the per-question cap.
    assertThat(outcomes.get(1)).asString().contains("未知维度");
    assertThat(outcomes.get(2)).isInstanceOf(QueryAgent.Values.class);
    assertThat(outcomes.get(3)).asString().contains("最多查询 3 次");
    assertThat(lookedUp).hasSize(2);
    assertThat(run.steps())
        .filteredOn(step -> step.name().equals("维度值查询"))
        .hasSize(2)
        .allSatisfy(step -> assertThat(step.detail()).doesNotContain("咨询"));
  }
}
