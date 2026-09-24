package io.matedata.semantic;

import static org.assertj.core.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class SemanticModelValidationTest {
  @Test
  void rejectsMissingBusinessVocabularyBeforePublishing() {
    assertThatThrownBy(() -> new SemanticModel.Metric("revenue", null, "amount", "SUM", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SemanticModel.Dimension("region", "  ", "region", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new SemanticModel.Metric("revenue", "销售额", "amount", null, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new SemanticModel.Dimension("region", "区域", "region", Arrays.asList("区域", null)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsMissingModelCollectionsAsInputErrors() {
    assertThatThrownBy(
            () -> new SemanticModel("sales", "销售", "", "demo_sales", "sales", null, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new SemanticModel("sales", "销售", "", "demo_sales", "sales", List.of(), null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
