package io.matedata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.matedata.conversation.*;
import io.matedata.conversation.application.QueryService;
import io.matedata.harness.QueryPlanner;
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
    var agent = mock(QueryPlanner.class);
    when(agent.plan(anyString(), any(), anyString(), anyString(), any()))
        .thenAnswer(
            invocation -> {
              entered.countDown();
              if (!release.await(5, TimeUnit.SECONDS))
                throw new IllegalArgumentException("test deadline");
              return new QueryPlan("revenue", "region", Map.of(), 10);
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
        new QueryService(f.models(), f.runs(), mock(QueryPlanner.class), executor, f.access());
    var result = service.ask("alice", "各区域销售额", "sales", "demo", null);
    assertThat(result.status()).isEqualTo("FAILED");
    assertThat(result.rows()).isEmpty();
    assertThat(result.toString()).doesNotContain("restricted-result-value");
    verify(f.runs()).save("alice", result);
  }
}
