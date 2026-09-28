package io.matedata.semantic;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class AnswerGroundingTest {
  final List<Map<String, Object>> rows =
      List.of(Map.of("region", "华东", "revenue", new BigDecimal("1449000.00")));

  @Test
  void writtenNumbersAndUnsupportedScalesCannotPassWithoutNumericEvidence() {
    assertThat(AnswerGrounding.supported("共五笔订单", rows)).isFalse();
    assertThat(AnswerGrounding.supported("销售额1千", List.of(Map.of("revenue", 1)))).isFalse();
    assertThat(AnswerGrounding.supported("销售额９９９元", rows)).isFalse();
    assertThat(AnswerGrounding.supported("Revenue is five million", rows)).isFalse();
    assertThat(AnswerGrounding.clarification("请问销售额一百万元，还要查看哪个区域？")).doesNotContain("一百万");
  }

  @Test
  void formattedAndScaledExactAmountsHaveEvidence() {
    assertThat(AnswerGrounding.supported("华东销售额为1,449,000.00元", rows)).isTrue();
    assertThat(AnswerGrounding.supported("华东销售额为144.9万元", rows)).isTrue();
  }

  @Test
  void unknownNumbersRatesChineseAmountsAndEmptyResultsAreRejected() {
    for (String answer : List.of("华东销售额为9999", "同比增长42%", "同比增长", "销售额一百万元"))
      assertThat(AnswerGrounding.supported(answer, rows)).as(answer).isFalse();
    assertThat(AnswerGrounding.supported("销售额为1449000", List.of())).isFalse();
  }

  @Test
  void clarificationsCannotSmuggleAnUnsupportedStatement() {
    assertThat(AnswerGrounding.clarification("你想按哪个维度查看利润？")).isEqualTo("你想按哪个维度查看利润？");
    assertThat(AnswerGrounding.clarification("华东最高，你想查看吗？")).doesNotContain("华东最高");
    assertThat(AnswerGrounding.clarification("你想看999万的销售额吗？")).doesNotContain("999");
  }
}
