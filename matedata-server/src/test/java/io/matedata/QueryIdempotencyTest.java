package io.matedata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.matedata.conversation.*;
import io.matedata.conversation.application.QueryService;
import io.matedata.conversation.infrastructure.JdbcRunRepository;
import io.matedata.harness.QueryAgent;
import io.matedata.identity.*;
import io.matedata.identity.application.DataAccessService;
import io.matedata.semantic.*;
import io.matedata.shared.ApplicationException;
import io.matedata.shared.infrastructure.DocumentStore;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class QueryIdempotencyTest {
  final ModelRepository models = mock(ModelRepository.class);
  final AccountRepository accounts = mock(AccountRepository.class);
  final QueryExecutor executor = mock(QueryExecutor.class);
  final RunRepository runs =
      new JdbcRunRepository(
          new DocumentStore(
              new JdbcTemplate(
                  new DriverManagerDataSource(
                      "jdbc:h2:mem:idempotency_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                      "sa",
                      ""))));

  QueryService service(QueryAgent agent) throws Exception {
    when(models.find("sales")).thenReturn(Optional.of(SemanticModel.sales()));
    for (String user : List.of("alice", "bob"))
      when(accounts.find(user)).thenReturn(Optional.of(new Account(user, user, "ADMIN", "hash")));
    when(executor.execute(any(), any(), any()))
        .thenReturn(
            new QueryExecutor.Result(
                List.of("region", "revenue"), List.of(Map.of("region", "华东", "revenue", 1))));
    return new QueryService(
        models,
        runs,
        agent,
        executor,
        new DataAccessService(accounts, mock(GrantRepository.class), models));
  }

  @Test
  void persistedKeySurvivesServiceRestartAndIsIsolatedByUser() throws Exception {
    var first = service(mock(QueryAgent.class));
    var run = first.ask("alice", "各区域销售额", "sales", "demo", null, "key-one");
    var restarted = service(mock(QueryAgent.class));
    assertThat(restarted.ask("alice", "各区域销售额", "sales", "demo", null, "key-one")).isEqualTo(run);
    var other = restarted.ask("bob", "各区域销售额", "sales", "demo", null, "key-one");
    assertThat(other.id()).isNotEqualTo(run.id());
    verify(executor, times(2)).execute(any(), any(), any());
  }

  @Test
  void changedPayloadConflictsAndRevocationPreventsReplay() throws Exception {
    var service = service(mock(QueryAgent.class));
    service.ask("alice", "各区域销售额", "sales", "demo", null, "same-key");
    assertThatThrownBy(() -> service.ask("alice", "各区域利润", "sales", "demo", null, "same-key"))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            e -> assertThat(e.kind()).isEqualTo(ApplicationException.Kind.CONFLICT));
    when(accounts.find("alice")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.ask("alice", "各区域销售额", "sales", "demo", null, "same-key"))
        .isInstanceOf(ApplicationException.class);
    verify(executor).execute(any(), any(), any());
  }

  @Test
  void concurrentIdenticalRequestsExecuteOnlyOnce() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    QueryAgent agent =
        (turn, query, observer) -> {
          entered.countDown();
          try {
            if (!release.await(4, TimeUnit.SECONDS)) throw new IllegalStateException("deadline");
          } catch (InterruptedException e) {
            throw new IllegalStateException(e);
          }
          query.run(new QueryPlan("revenue", "region", Map.of(), 10));
          return new QueryAgent.Reply("");
        };
    var service = service(agent);
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = pool.submit(() -> service.ask("alice", "销售额", "sales", "agent", null, "repeat"));
      try {
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        var second =
            pool.submit(() -> service.ask("alice", "销售额", "sales", "agent", null, "repeat"));
        release.countDown();
        assertThat(second.get(3, TimeUnit.SECONDS)).isEqualTo(first.get(3, TimeUnit.SECONDS));
      } finally {
        release.countDown();
      }
    }
    verify(executor).execute(any(), any(), any());
  }

  @Test
  void failedTurnReleasesConversationAndSavedFailureIsReplayed() throws Exception {
    var fail = new AtomicBoolean(true);
    var service =
        service(
            (turn, query, observer) -> {
              if (fail.getAndSet(false)) throw new IllegalArgumentException("failed turn");
              query.run(new QueryPlan("revenue", "region", Map.of(), 10));
              return new QueryAgent.Reply("");
            });
    var failed = service.ask("alice", "销售额", "sales", "agent", "chat", "failed");
    assertThat(failed.status()).isEqualTo("FAILED");
    assertThat(service.ask("alice", "销售额", "sales", "agent", "chat", "failed")).isEqualTo(failed);
    assertThat(service.ask("alice", "销售额", "sales", "agent", "chat", "next").status())
        .isEqualTo("SUCCEEDED");
    verify(executor).execute(any(), any(), any());
  }
}
