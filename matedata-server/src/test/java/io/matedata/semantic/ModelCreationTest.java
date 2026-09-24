package io.matedata.semantic;

import static org.assertj.core.api.Assertions.*;

import io.matedata.catalog.application.CatalogService;
import io.matedata.catalog.domain.DataSourceDefinition;
import io.matedata.catalog.domain.SourceTable;
import io.matedata.catalog.infrastructure.BusinessConnections;
import io.matedata.catalog.infrastructure.JdbcSourceRepository;
import io.matedata.semantic.application.SemanticService;
import io.matedata.semantic.infrastructure.JdbcModelRepository;
import io.matedata.shared.ApplicationException;
import io.matedata.shared.infrastructure.DocumentStore;
import io.matedata.shared.infrastructure.SecretVault;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ModelCreationTest {
  @TempDir Path directory;

  private DocumentStore store() {
    return new DocumentStore(
        new JdbcTemplate(
            new DriverManagerDataSource(
                "jdbc:h2:mem:model_creation_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                "sa",
                "")));
  }

  private SemanticModel candidate(String name, String aggregation) {
    var sales = SemanticModel.sales();
    return new SemanticModel(
        "custom_sales",
        name,
        name + " description",
        sales.sourceId(),
        sales.tableName(),
        List.of(
            new SemanticModel.Metric(
                "revenue", name + " metric", "amount", aggregation, List.of(name))),
        sales.dimensions());
  }

  @Test
  void concurrentCreationHasOneWinnerAndPreservesItsCompletePublishedModel() throws Exception {
    var store = store();
    var repository = new JdbcModelRepository(store);
    var vault = new SecretVault(directory.toString(), "");
    var barrier = new CyclicBarrier(2);
    var connections =
        new BusinessConnections(vault) {
          @Override
          public List<SourceTable> tables(DataSourceDefinition source) {
            var tables = super.tables(source);
            try {
              barrier.await(10, TimeUnit.SECONDS);
            } catch (Exception e) {
              throw new AssertionError("Both create calls must reach metadata validation", e);
            }
            return tables;
          }
        };
    var catalog = new CatalogService(new JdbcSourceRepository(store), vault, connections);
    var firstService = new SemanticService(repository, catalog);
    var secondService = new SemanticService(new JdbcModelRepository(store), catalog);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var first = executor.submit(() -> create(firstService, candidate("First", "SUM")));
      var second = executor.submit(() -> create(secondService, candidate("Second", "AVG")));
      var results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
      assertThat(results.stream().filter(SemanticModel.class::isInstance)).hasSize(1);
      assertThat(results.stream().filter(ApplicationException.class::isInstance)).hasSize(1);
      var failure =
          (ApplicationException)
              results.stream()
                  .filter(ApplicationException.class::isInstance)
                  .findFirst()
                  .orElseThrow();
      assertThat(failure.kind()).isEqualTo(ApplicationException.Kind.CONFLICT);
      var winner =
          (SemanticModel)
              results.stream().filter(SemanticModel.class::isInstance).findFirst().orElseThrow();
      assertThat(repository.find("custom_sales")).contains(winner);
      assertThat(winner.tableName()).isEqualTo("SALES");
      assertThat(winner.metrics().getFirst().column()).isEqualTo("AMOUNT");
    }
  }

  @Test
  void duplicateCreationPreservesOriginalWhileExplicitUpdateStillReplacesIt() throws Exception {
    var store = store();
    var repository = new JdbcModelRepository(store);
    var vault = new SecretVault(directory.toString(), "");
    var service =
        new SemanticService(
            repository,
            new CatalogService(
                new JdbcSourceRepository(store), vault, new BusinessConnections(vault)));
    var original = service.create(candidate("Original", "SUM"));
    assertThatThrownBy(() -> service.create(candidate("Replacement", "AVG")))
        .isInstanceOfSatisfying(
            ApplicationException.class,
            e -> assertThat(e.kind()).isEqualTo(ApplicationException.Kind.CONFLICT));
    assertThat(repository.find(original.id())).contains(original);
    var updated = service.update(original.id(), candidate("Replacement", "AVG"));
    assertThat(repository.find(original.id())).contains(updated);
    assertThat(updated.name()).isEqualTo("Replacement");
    assertThat(updated.metrics().getFirst().aggregation()).isEqualTo("AVG");
  }

  private Object create(SemanticService service, SemanticModel candidate) {
    try {
      return service.create(candidate);
    } catch (ApplicationException e) {
      return e;
    }
  }
}
