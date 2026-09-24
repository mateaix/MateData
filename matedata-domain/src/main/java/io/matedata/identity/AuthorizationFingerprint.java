package io.matedata.identity;

import io.matedata.semantic.SemanticModel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Historical results require the same semantic model and authorization scope. */
public final class AuthorizationFingerprint {
  private AuthorizationFingerprint() {}

  public static String of(SemanticModel model, DatasetGrant grant) {
    var value = new StringBuilder("v2:");
    fields(
        value,
        model.id(),
        model.name(),
        model.description(),
        model.sourceId(),
        model.tableName(),
        Objects.toString(model.dialect(), null));
    fields(value, Integer.toString(model.metrics().size()));
    for (var metric : model.metrics()) {
      fields(value, metric.id(), metric.name(), metric.column(), metric.aggregation());
      sequence(value, metric.aliases());
    }
    fields(value, Integer.toString(model.dimensions().size()));
    for (var dimension : model.dimensions()) {
      fields(
          value,
          dimension.id(),
          dimension.name(),
          dimension.column(),
          dimension.valueType().name());
      sequence(value, dimension.aliases());
    }
    fields(value, grant.username(), grant.datasetId(), Boolean.toString(grant.enabled()));
    sequence(value, new TreeSet<>(grant.metrics()));
    sequence(value, new TreeSet<>(grant.dimensions()));
    fields(value, Integer.toString(grant.rowFilters().size()));
    new TreeMap<>(grant.rowFilters()).forEach((key, filter) -> fields(value, key, filter));
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void sequence(StringBuilder target, Collection<String> values) {
    fields(target, Integer.toString(values.size()));
    values.forEach(value -> fields(target, value));
  }

  private static void fields(StringBuilder target, String... values) {
    for (String value : values) {
      if (value == null) target.append("-1:");
      else target.append(value.length()).append(':').append(value);
    }
  }
}
