package io.matedata;

import static org.assertj.core.api.Assertions.*;

import io.matedata.conversation.QueryRun;
import io.matedata.identity.*;
import io.matedata.identity.application.DataAccessService;
import io.matedata.identity.infrastructure.*;
import io.matedata.semantic.*;
import io.matedata.semantic.infrastructure.JdbcModelRepository;
import io.matedata.shared.ApplicationException;
import io.matedata.shared.infrastructure.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class AccuracyRegressionTest {
  DocumentStore store() {
    return new DocumentStore(
        new JdbcTemplate(
            new DriverManagerDataSource(
                "jdbc:h2:mem:accuracy_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "")));
  }

  @Test
  void durableRunsPreserveFinancialPrecision() {
    var store = store();
    var value = new BigDecimal("9007199254740993.01");
    var run =
        new QueryRun(
            "run",
            "conversation",
            "q",
            "sales",
            "demo",
            "SUCCEEDED",
            "sql",
            List.of("revenue"),
            List.of(Map.of("revenue", value)),
            1,
            1,
            "2026-09-24T00:00:00Z",
            "",
            null,
            List.of());
    store.save("test", "run", run);
    var restored =
        store.get("test", "run", QueryRun.class).orElseThrow().rows().getFirst().get("revenue");
    assertThat(restored).isInstanceOf(BigDecimal.class);
    assertThat((BigDecimal) restored).isEqualByComparingTo(value);
    assertThat(
            io.matedata.conversation.interfaces.RunResponses.safeNumbers(run)
                .rows()
                .getFirst()
                .get("revenue"))
        .isEqualTo("9007199254740993.01");
  }

  @Test
  void largeJdbcIntegersRemainExactOnTheWire() {
    var run =
        new QueryRun(
            "run",
            "c",
            "q",
            "sales",
            "demo",
            "SUCCEEDED",
            "sql",
            List.of("maximum"),
            List.of(Map.of("maximum", 9007199254740993L)),
            1,
            1,
            "2026-09-24T00:00:00Z",
            "",
            null,
            List.of());
    assertThat(
            io.matedata.conversation.interfaces.RunResponses.safeNumbers(run)
                .rows()
                .getFirst()
                .get("maximum"))
        .isEqualTo("9007199254740993");
  }

  @Test
  void demoNameDoesNotGrantAccessToRepointedProductionData() {
    var store = store();
    var accounts = new JdbcAccountRepository(store);
    accounts.save(new Account("analyst", "Analyst", "ANALYST", "unused"));
    var policy =
        new DataAccessService(
            accounts, new JdbcGrantRepository(store), new JdbcModelRepository(store));
    var demo = SemanticModel.sales();
    var repointed =
        new SemanticModel(
            "sales",
            "Live production",
            "",
            "live_source",
            "private_sales",
            demo.metrics(),
            demo.dimensions());
    assertThatThrownBy(() -> policy.require("analyst", repointed))
        .isInstanceOf(ApplicationException.class);
    assertThat(policy.require("analyst", demo).enabled()).isTrue();
  }

  @Test
  void rowScopeCannotBeOverriddenByRequestedFilter() {
    var store = store();
    var policy =
        new DataAccessService(
            new JdbcAccountRepository(store),
            new JdbcGrantRepository(store),
            new JdbcModelRepository(store));
    var grant =
        new DatasetGrant(
            "analyst",
            "sales",
            true,
            List.of("revenue"),
            List.of("region"),
            Map.of("region", "华东"));
    assertThatThrownBy(
            () ->
                policy.constrain(
                    SemanticModel.sales(),
                    grant,
                    new QueryPlan("revenue", "region", Map.of("region", "华南"), 100)))
        .isInstanceOf(ApplicationException.class);
  }

  @Test
  void historicalListingIsPaginatedWithoutDeserializingResultRows() {
    var repository = new io.matedata.conversation.infrastructure.JdbcRunRepository(store());
    for (int i = 0; i < 6; i++)
      repository.save(
          "alice",
          new QueryRun(
              "run" + i,
              "c",
              "q",
              "sales",
              "demo",
              "SUCCEEDED",
              "sql",
              List.of("revenue"),
              List.of(Map.of("revenue", 123)),
              1,
              1,
              "2026-09-24T00:00:00Z",
              "",
              null,
              List.of()));
    var page = repository.page("alice", 0, 2);
    assertThat(page).hasSize(2);
    assertThat(page)
        .allSatisfy(
            run -> {
              assertThat(run.rows()).isEmpty();
              assertThat(run.rowCount()).isEqualTo(1);
            });
    assertThat(repository.page("alice", 2, 2))
        .extracting(QueryRun::id)
        .doesNotContainAnyElementsOf(page.stream().map(QueryRun::id).toList());
    assertThat(repository.find("alice", page.getFirst().id()).orElseThrow().rows()).hasSize(1);
  }
}
