package io.matedata.conversation.application;

import io.matedata.conversation.*;
import io.matedata.harness.QueryPlanner;
import io.matedata.identity.AuthorizationFingerprint;
import io.matedata.identity.application.DataAccessService;
import io.matedata.semantic.*;
import io.matedata.shared.ApplicationException;
import io.matedata.shared.ApplicationException.Kind;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;

@Service
public class QueryService {
  private final ModelRepository models;
  private final RunRepository runs;
  private final QueryPlanner agent;
  private final QueryExecutor executor;
  private final Semaphore slots = new Semaphore(8);
  private final DataAccessService access;

  public QueryService(
      ModelRepository models,
      RunRepository runs,
      QueryPlanner agent,
      QueryExecutor executor,
      DataAccessService access) {
    this.models = models;
    this.runs = runs;
    this.agent = agent;
    this.executor = executor;
    this.access = access;
  }

  public QueryRun ask(
      String user, String question, String datasetId, String mode, String conversationId) {
    if (question == null || question.isBlank() || question.length() > 2000)
      throw new IllegalArgumentException("问题长度需为 1–2000 字符");
    if (!Set.of("demo", "agent").contains(mode == null ? "" : mode))
      throw new IllegalArgumentException("请选择 demo 或 agent 模式");
    var model =
        models
            .find(datasetId)
            .orElseThrow(() -> new ApplicationException(Kind.NOT_FOUND, "数据集不存在"));
    var grant = access.require(user, model);
    var permittedModel = access.restrict(model, grant);
    String fingerprint = AuthorizationFingerprint.of(model, grant);
    if (!slots.tryAcquire()) throw new ApplicationException(Kind.BUSY, "并发任务已满，请稍后重试");
    long start = System.nanoTime();
    String id = UUID.randomUUID().toString(), created = Instant.now().toString();
    String conversation = conversationId == null ? UUID.randomUUID().toString() : conversationId;
    if (conversation.length() > 64) {
      slots.release();
      throw new IllegalArgumentException("会话标识过长");
    }
    var steps = new java.util.concurrent.CopyOnWriteArrayList<QueryRun.Step>();
    String sql = "";
    String active = "语义解析";
    try {
      steps.add(new QueryRun.Step("语义检索", "SUCCEEDED", "数据集：" + model.name() + "；仅开放登记的指标与维度", 0));
      long t = System.nanoTime();
      QueryPlan requested =
          mode.equals("demo")
              ? new DemoPlanner().plan(question, permittedModel)
              : agent.plan(
                  question,
                  permittedModel,
                  user,
                  id,
                  step ->
                      steps.add(
                          new QueryRun.Step(
                              step.name(), step.status(), step.detail(), step.durationMs())));
      QueryPlan plan = access.constrain(model, grant, requested);
      steps.add(
          new QueryRun.Step(
              "数据权限", "SUCCEEDED", "指标和维度权限已校验；强制行级过滤 " + grant.rowFilters().size() + " 项", 0));
      steps.add(
          new QueryRun.Step(
              active,
              "SUCCEEDED",
              mode.equals("demo") ? "确定性演示解析（无模型调用）" : "AgentScope Harness 生成受控语义计划",
              elapsed(t)));
      active = "SQL 校验";
      t = System.nanoTime();
      var compiled = new SemanticCompiler().compile(model, plan);
      new SqlGuard().verify(compiled, model, plan);
      sql = compiled.sql();
      steps.add(
          new QueryRun.Step(
              active,
              "SUCCEEDED",
              "AST 校验 + 语义计划一致性校验；只读、参数绑定、最多 " + plan.limit() + " 行",
              elapsed(t)));
      verifyScope(user, datasetId, fingerprint);
      active = "执行查询";
      t = System.nanoTime();
      var result = executor.execute(model, plan, compiled);
      verifyScope(user, datasetId, fingerprint);
      steps.add(
          new QueryRun.Step(
              active,
              "SUCCEEDED",
              "返回 " + result.rows().size() + " 行；语句执行超时 10 秒；分批读取",
              elapsed(t)));
      String answer =
          "已基于「"
              + model.name()
              + "」计算"
              + model.metric(plan.metric()).name()
              + "，共返回 "
              + result.rows().size()
              + " 行。";
      var run =
          new QueryRun(
                  id,
                  conversation,
                  question,
                  datasetId,
                  mode,
                  "SUCCEEDED",
                  sql,
                  result.columns(),
                  result.rows(),
                  result.rows().size(),
                  elapsed(start),
                  created,
                  answer,
                  null,
                  steps)
              .withScope(fingerprint);
      runs.save(user, run);
      return run;
    } catch (Exception e) {
      String error =
          e instanceof IllegalArgumentException ? e.getMessage() : "查询执行失败，请检查数据源、语义映射或模型连接";
      steps.add(new QueryRun.Step(active, "FAILED", error, 0));
      var run =
          new QueryRun(
                  id,
                  conversation,
                  question,
                  datasetId,
                  mode,
                  "FAILED",
                  sql,
                  List.of(),
                  List.of(),
                  0,
                  elapsed(start),
                  created,
                  "",
                  error,
                  steps)
              .withScope(fingerprint);
      runs.save(user, run);
      return run;
    } finally {
      slots.release();
    }
  }

  public RunPage historyPage(String user, int offset, int limit) {
    if (offset < 0 || offset > 100000 || limit < 1 || limit > 100)
      throw new IllegalArgumentException("分页参数无效，每页最多100条");
    var scanned = runs.page(user, offset, limit);
    var visible =
        scanned.stream().filter(run -> canRead(user, run)).map(QueryRun::summary).toList();
    Integer next = scanned.size() == limit && offset + limit <= 100000 ? offset + limit : null;
    return new RunPage(visible, next);
  }

  public List<QueryRun> history(String user) {
    return history(user, 0, 50);
  }

  public List<QueryRun> history(String user, int offset, int limit) {
    return historyPage(user, offset, limit).items();
  }

  public QueryRun get(String user, String id) {
    var run =
        runs.find(user, id).orElseThrow(() -> new ApplicationException(Kind.NOT_FOUND, "运行记录不存在"));
    verifyScope(user, run.datasetId(), run.scopeFingerprint());
    return run;
  }

  private boolean canRead(String user, QueryRun run) {
    try {
      verifyScope(user, run.datasetId(), run.scopeFingerprint());
      return true;
    } catch (ApplicationException e) {
      return false;
    }
  }

  private void verifyScope(String user, String dataset, String expected) {
    var model =
        models
            .find(dataset)
            .orElseThrow(() -> new ApplicationException(Kind.FORBIDDEN, "数据集已不可用，请重新查询"));
    String current = AuthorizationFingerprint.of(model, access.require(user, model));
    if (!current.equals(expected))
      throw new ApplicationException(Kind.FORBIDDEN, "数据权限或语义模型已变更，请重新查询");
  }

  private static long elapsed(long start) {
    return (System.nanoTime() - start) / 1_000_000;
  }
}
