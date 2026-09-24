package io.matedata.semantic;

import java.math.BigDecimal;
import java.time.*;

/** Scalar types used for parameter binding, independent of JDBC and provider-specific names. */
public enum ValueType {
  TEXT,
  NUMBER,
  BOOLEAN,
  DATE,
  TIME,
  TIMESTAMP,
  TIMESTAMP_WITH_ZONE;

  public Object parse(String value) {
    if (value == null || value.length() > 200)
      throw new IllegalArgumentException("过滤值不能为空或超过 200 字符");
    try {
      return switch (this) {
        case TEXT -> value;
        case NUMBER -> {
          var number = new BigDecimal(value);
          if (number.precision() > 1000 || number.scale() < -1000 || number.scale() > 1000)
            throw new IllegalArgumentException();
          yield number;
        }
        case BOOLEAN -> {
          if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false"))
            throw new IllegalArgumentException();
          yield Boolean.valueOf(value);
        }
        case DATE -> LocalDate.parse(value);
        case TIME -> LocalTime.parse(value);
        case TIMESTAMP -> LocalDateTime.parse(value);
        case TIMESTAMP_WITH_ZONE -> OffsetDateTime.parse(value);
      };
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("过滤值不符合字段类型 " + name() + "；日期时间请使用 ISO 格式");
    }
  }
}
