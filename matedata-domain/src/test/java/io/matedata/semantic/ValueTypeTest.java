package io.matedata.semantic;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.*;
import org.junit.jupiter.api.Test;

class ValueTypeTest {
  @Test
  void convertsExactScalarParametersWithoutLosingDigits() {
    assertThat(ValueType.NUMBER.parse("9007199254740993.01"))
        .isEqualTo(new BigDecimal("9007199254740993.01"));
    assertThat(ValueType.DATE.parse("2026-01-15")).isEqualTo(LocalDate.of(2026, 1, 15));
    assertThat(ValueType.BOOLEAN.parse("true")).isEqualTo(true);
  }

  @Test
  void rejectsMalformedAndUnboundedScalarsBeforeJdbc() {
    assertThatThrownBy(() -> ValueType.NUMBER.parse("1e999999999"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ValueType.NUMBER.parse("not-a-number"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ValueType.DATE.parse("2026-02-31"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ValueType.BOOLEAN.parse("yes"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
