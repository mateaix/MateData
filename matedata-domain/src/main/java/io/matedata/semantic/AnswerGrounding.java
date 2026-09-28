package io.matedata.semantic;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Conservative numeric evidence check, not a proof of natural-language semantics or causality. */
public final class AnswerGrounding {
  private static final Pattern NUMBER =
      Pattern.compile("[-+]?\\d+(?:,\\d{3})*(?:\\.\\d+)?(?:[eE][-+]?\\d+)?");
  private static final Pattern UNSUPPORTED =
      Pattern.compile(
          "[%％]|百分之|同比|环比|增长率|下降率|\\b(?:zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|thousand|million|billion|trillion)\\b",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern WRITTEN_NUMBER =
      Pattern.compile("[零〇一二三四五六七八九十百千万亿两壹贰叁肆伍陆柒捌玖拾佰仟萬億]");
  private static final Pattern SUPPORTED_QUANTITY =
      Pattern.compile(NUMBER.pattern() + "(?:\\s*[万亿])?");

  private static boolean unsupportedUnicodeNumber(String text) {
    return text.codePoints()
        .anyMatch(
            c ->
                (Character.isDigit(c) && (c < '0' || c > '9'))
                    || Character.getType(c) == Character.OTHER_NUMBER
                    || Character.getType(c) == Character.LETTER_NUMBER);
  }

  private AnswerGrounding() {}

  public static String clarification(String answer) {
    String text = answer == null ? "" : answer.strip();
    if (text.matches("(?:你想|您想|请问|想按|要按|需要)[^。！!\\n？?]*[？?]")
        && !NUMBER.matcher(text).find()
        && !WRITTEN_NUMBER.matcher(text).find()
        && !UNSUPPORTED.matcher(text).find()
        && !unsupportedUnicodeNumber(text)) return text;
    return "当前未执行数据查询，无法给出数据结论。请明确要分析的指标、维度或筛选条件。";
  }

  public static boolean supported(String answer, List<Map<String, Object>> rows) {
    if (answer == null || answer.isBlank() || rows.isEmpty()) return false;
    if (UNSUPPORTED.matcher(answer).find() || unsupportedUnicodeNumber(answer)) return false;
    // Remove supported Arabic quantities first: 144.9万元 is valid, 五笔 or 1千 is not.
    if (WRITTEN_NUMBER.matcher(SUPPORTED_QUANTITY.matcher(answer).replaceAll("")).find())
      return false;
    Set<BigDecimal> evidence = new HashSet<>();
    for (var row : rows)
      for (var value : row.values()) {
        if (value == null) continue;
        var matcher = NUMBER.matcher(value.toString());
        while (matcher.find()) {
          try {
            evidence.add(number(matcher.group()));
          } catch (NumberFormatException ignored) {
            /* Not usable numeric evidence. */
          }
        }
      }
    var matcher = NUMBER.matcher(answer);
    while (matcher.find()) {
      BigDecimal value;
      try {
        value = number(matcher.group());
      } catch (NumberFormatException e) {
        return false;
      }
      String suffix = answer.substring(matcher.end()).stripLeading();
      if (suffix.startsWith("万")) value = value.multiply(BigDecimal.valueOf(10000));
      else if (suffix.startsWith("亿")) value = value.multiply(BigDecimal.valueOf(100000000));
      if (!evidence.contains(value.stripTrailingZeros())) return false;
    }
    return true;
  }

  private static BigDecimal number(String text) {
    return new BigDecimal(text.replace(",", "")).stripTrailingZeros();
  }
}
