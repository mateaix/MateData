package io.matedata;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.matedata.conversation.*;
import io.matedata.conversation.application.QueryService;
import io.matedata.harness.QueryAgent;
import io.matedata.identity.*;
import io.matedata.identity.application.DataAccessService;
import io.matedata.semantic.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class HistoryPageTest {
  @Test
  void continuationSurvivesAWholePageOfInvalidatedScopes() {
    var model = SemanticModel.sales();
    var models = mock(ModelRepository.class);
    when(models.find("sales")).thenReturn(Optional.of(model));
    var accounts = mock(AccountRepository.class);
    when(accounts.find("alice"))
        .thenReturn(Optional.of(new Account("alice", "Alice", "ADMIN", "hash")));
    var access = new DataAccessService(accounts, mock(GrantRepository.class), models);
    var run =
        new QueryRun(
            "one",
            "c",
            "q",
            "sales",
            "demo",
            "SUCCEEDED",
            "",
            List.of(),
            List.of(),
            0,
            1,
            "2026-09-24T00:00:00Z",
            "",
            null,
            List.of());
    var revoked = run.withScope("old-scope");
    var allowed = run.withScope(AuthorizationFingerprint.of(model, access.require("alice", model)));
    var runs = mock(RunRepository.class);
    when(runs.page("alice", 0, 2)).thenReturn(List.of(revoked, revoked));
    when(runs.page("alice", 2, 2)).thenReturn(List.of(allowed));
    var service =
        new QueryService(models, runs, mock(QueryAgent.class), mock(QueryExecutor.class), access);
    var first = service.historyPage("alice", 0, 2);
    assertThat(first.items()).isEmpty();
    assertThat(first.nextOffset()).isEqualTo(2);
    var second = service.historyPage("alice", first.nextOffset(), 2);
    assertThat(second.items()).hasSize(1);
    assertThat(second.nextOffset()).isNull();
  }
}
