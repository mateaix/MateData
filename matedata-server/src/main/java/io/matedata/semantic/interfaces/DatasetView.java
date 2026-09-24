package io.matedata.semantic.interfaces;

import io.matedata.identity.application.DataAccessService.ScopedModel;
import io.matedata.semantic.SemanticModel;
import java.util.List;

/** The opaque fingerprint lets clients invalidate cached results without exposing row policies. */
public record DatasetView(
    String id,
    String name,
    String description,
    String sourceId,
    String tableName,
    List<SemanticModel.Metric> metrics,
    List<SemanticModel.Dimension> dimensions,
    SemanticModel.Dialect dialect,
    String scopeFingerprint) {
  static DatasetView from(ScopedModel scoped, String publicFingerprint) {
    var model = scoped.model();
    return new DatasetView(
        model.id(),
        model.name(),
        model.description(),
        model.sourceId(),
        model.tableName(),
        model.metrics(),
        model.dimensions(),
        model.dialect(),
        publicFingerprint);
  }
}
