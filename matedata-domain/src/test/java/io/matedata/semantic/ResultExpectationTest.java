package io.matedata.semantic;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class ResultExpectationTest {
  @Test
  void rejectsTruncatedAndNumericallyWrongAnswers() {
    var expected = Map.of("华东", new BigDecimal("1449000"), "华南", new BigDecimal("1337400"));
    assertThat(
            ResultExpectation.matches(
                "region", "revenue", expected, List.of(Map.of("region", "华东", "revenue", 1449000))))
        .isFalse();
    assertThat(
            ResultExpectation.matches(
                "region",
                "revenue",
                expected,
                List.of(
                    Map.of("region", "华东", "revenue", 1449000),
                    Map.of("region", "华南", "revenue", 1))))
        .isFalse();
  }

  @Test
  void comparesNormalizedNumbersWithoutDependingOnRowOrder() {
    var expected = Map.of("华东", new BigDecimal("1449000"), "华南", new BigDecimal("1337400"));
    assertThat(
            ResultExpectation.matches(
                "region",
                "revenue",
                expected,
                List.of(
                    Map.of("region", "华南", "revenue", new BigDecimal("1337400.00")),
                    Map.of("region", "华东", "revenue", 1449000L))))
        .isTrue();
  }
}
