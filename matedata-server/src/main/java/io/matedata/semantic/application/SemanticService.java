package io.matedata.semantic.application;

import io.matedata.catalog.application.CatalogService;
import io.matedata.semantic.*;
import io.matedata.shared.ApplicationException;
import io.matedata.shared.ApplicationException.Kind;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class SemanticService {
  private final ModelRepository models;
  private final CatalogService catalog;

  public SemanticService(ModelRepository models, CatalogService catalog) {
    this.models = models;
    this.catalog = catalog;
  }

  public List<SemanticModel> all() {
    return models.all();
  }

  public SemanticModel create(SemanticModel model) {
    if (models.find(model.id()).isPresent())
      throw new ApplicationException(Kind.CONFLICT, "数据集标识已存在");
    var published = canonical(model);
    models.save(published);
    return published;
  }

  public SemanticModel update(String id, SemanticModel model) {
    if (!id.equals(model.id())) throw new IllegalArgumentException("数据集标识不一致");
    if (models.find(id).isEmpty()) throw new ApplicationException(Kind.NOT_FOUND, "数据集不存在");
    var published = canonical(model);
    models.save(published);
    return published;
  }

  private SemanticModel canonical(SemanticModel model) {
    var source = catalog.require(model.sourceId());
    var tables = catalog.tables(model.sourceId());
    String tableName =
        resolve(
            model.tableName(),
            tables.stream().map(io.matedata.catalog.domain.SourceTable::name).toList());
    var table = tables.stream().filter(t -> t.name().equals(tableName)).findFirst().orElseThrow();
    var columns =
        table.columns().stream().map(io.matedata.catalog.domain.SourceColumn::name).toList();
    var metrics =
        model.metrics().stream()
            .map(
                m ->
                    new SemanticModel.Metric(
                        m.id(),
                        m.name(),
                        resolve(m.column(), columns),
                        m.aggregation(),
                        m.aliases()))
            .toList();
    var dimensions =
        model.dimensions().stream()
            .map(
                d ->
                    new SemanticModel.Dimension(
                        d.id(),
                        d.name(),
                        resolve(d.column(), columns),
                        d.aliases(),
                        valueType(table, resolve(d.column(), columns))))
            .toList();
    return new SemanticModel(
        model.id(),
        model.name(),
        model.description(),
        model.sourceId(),
        tableName,
        metrics,
        dimensions,
        source.type().equals("MYSQL") ? SemanticModel.Dialect.MYSQL : SemanticModel.Dialect.ANSI);
  }

  private ValueType valueType(io.matedata.catalog.domain.SourceTable table, String column) {
    String type =
        table.columns().stream()
            .filter(c -> c.name().equals(column))
            .findFirst()
            .orElseThrow()
            .type()
            .toUpperCase(Locale.ROOT);
    if (type.equals("TIMESTAMPTZ") || type.contains("TIMESTAMP WITH TIME ZONE"))
      return ValueType.TIMESTAMP_WITH_ZONE;
    if (type.startsWith("TIMESTAMP") || type.equals("DATETIME")) return ValueType.TIMESTAMP;
    if (type.equals("DATE")) return ValueType.DATE;
    if (type.equals("TIME") || type.equals("TIME WITHOUT TIME ZONE")) return ValueType.TIME;
    if (type.equals("BOOLEAN") || type.equals("BOOL")) return ValueType.BOOLEAN;
    if (type.matches(
        "(TINYINT|SMALLINT|MEDIUMINT|INTEGER|INT|BIGINT|INT[248]|SERIAL|BIGSERIAL|DECIMAL|NUMERIC|REAL|FLOAT[48]?|DOUBLE( PRECISION)?)( UNSIGNED)?"))
      return ValueType.NUMBER;
    return ValueType.TEXT;
  }

  private String resolve(String requested, List<String> names) {
    if (names.contains(requested)) return requested;
    var matches = names.stream().filter(n -> n.equalsIgnoreCase(requested)).toList();
    if (matches.size() != 1) throw new IllegalArgumentException("物理表或列不存在、无法访问，或存在大小写歧义");
    return matches.getFirst();
  }
}
