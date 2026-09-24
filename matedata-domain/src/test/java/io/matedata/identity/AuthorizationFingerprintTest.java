package io.matedata.identity;

import static org.assertj.core.api.Assertions.*;

import io.matedata.semantic.SemanticModel;
import java.util.*;
import org.junit.jupiter.api.Test;

class AuthorizationFingerprintTest {
  @Test
  void filterDelimitersCannotCollideWithAdditionalEntries() {
    var model = SemanticModel.sales();
    var one =
        new DatasetGrant(
            "alice",
            "sales",
            true,
            List.of("revenue"),
            List.of("region", "channel"),
            Map.of("channel", "b, region=a"));
    var two =
        new DatasetGrant(
            "alice",
            "sales",
            true,
            List.of("revenue"),
            List.of("region", "channel"),
            Map.of("region", "a", "channel", "b"));
    assertThat(AuthorizationFingerprint.of(model, one))
        .isNotEqualTo(AuthorizationFingerprint.of(model, two));
  }

  @Test
  void grantOrderingDoesNotInvalidateHistory() {
    var first =
        new DatasetGrant(
            "alice",
            "sales",
            true,
            List.of("revenue", "orders"),
            List.of("region", "channel"),
            Map.of("region", "a", "channel", "b"));
    var second =
        new DatasetGrant(
            "alice",
            "sales",
            true,
            List.of("orders", "revenue"),
            List.of("channel", "region"),
            Map.of("channel", "b", "region", "a"));
    assertThat(AuthorizationFingerprint.of(SemanticModel.sales(), first))
        .isEqualTo(AuthorizationFingerprint.of(SemanticModel.sales(), second));
  }
}
