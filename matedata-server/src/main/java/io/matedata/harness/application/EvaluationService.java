package io.matedata.harness.application;

import io.matedata.conversation.application.QueryService;
import io.matedata.harness.*;
import io.matedata.semantic.ResultExpectation;
import io.matedata.shared.ApplicationException;
import io.matedata.shared.ApplicationException.Kind;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class EvaluationService {
  private final QueryService queries;
  private final EvaluationRepository repository;
  private final AuditService audit;

  public EvaluationService(
      QueryService queries, EvaluationRepository repository, AuditService audit) {
    this.queries = queries;
    this.repository = repository;
    this.audit = audit;
  }

  public List<EvaluationCase> cases() {
    return List.of(
        new EvaluationCase("region_revenue", "区域销售额", "各区域销售额", "sales", "revenue", "region"),
        new EvaluationCase("category_profit", "品类利润", "各品类利润", "sales", "profit", "category"),
        new EvaluationCase("month_revenue", "销售趋势", "每月销售额趋势", "sales", "revenue", "month"),
        new EvaluationCase("channel_orders", "渠道订单量", "各渠道订单数", "sales", "orders", "channel"));
  }

  public List<EvaluationReport> all(String user) {
    return repository.all(user);
  }

  public EvaluationReport get(String user, String id) {
    return repository
        .find(user, id)
        .orElseThrow(() -> new ApplicationException(Kind.NOT_FOUND, "评测记录不存在"));
  }

  public EvaluationReport run(String user, String mode) {
    long start = System.nanoTime();
    var results = new ArrayList<EvaluationReport.Result>();
    for (var test : cases()) {
      var run = queries.ask(user, test.question(), test.datasetId(), mode, null);
      boolean pass =
          run.status().equals("SUCCEEDED")
              && ResultExpectation.matches(
                  test.expectedDimension(), test.expectedMetric(), expected(test.id()), run.rows());
      results.add(
          new EvaluationReport.Result(
              test.name(),
              pass,
              pass
                  ? "分组数量、维度值和数值与固定基准完全一致"
                  : Objects.toString(run.error(), "结果不符合基准：请检查过滤条件、结果上限和指标定义"),
              run.id()));
    }
    var report =
        new EvaluationReport(
            UUID.randomUUID().toString(),
            results.stream().filter(EvaluationReport.Result::passed).count(),
            results.size(),
            (System.nanoTime() - start) / 1_000_000,
            results);
    repository.save(user, report);
    audit.record(user, "EVALUATION_RUN", report.id());
    return report;
  }

  private Map<String, BigDecimal> expected(String id) {
    Map<String, Long> values =
        switch (id) {
          case "region_revenue" ->
              Map.of("华东", 1449000L, "华南", 1337400L, "华北", 1225800L, "西部", 1114200L);
          case "category_profit" -> Map.of("软件服务", 455616L, "智能硬件", 478464L, "专业咨询", 501312L);
          case "month_revenue" ->
              Map.of(
                  "2026-01", 722400L, "2026-02", 775200L, "2026-03", 828000L, "2026-04", 880800L,
                  "2026-05", 933600L, "2026-06", 986400L);
          case "channel_orders" -> Map.of("直销", 72L, "合作伙伴", 72L);
          default -> throw new IllegalArgumentException("未知评测用例");
        };
    var result = new HashMap<String, BigDecimal>();
    values.forEach((key, value) -> result.put(key, BigDecimal.valueOf(value)));
    return result;
  }
}
