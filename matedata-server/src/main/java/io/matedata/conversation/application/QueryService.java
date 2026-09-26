package io.matedata.conversation.application;

import io.matedata.conversation.*;
import io.matedata.harness.QueryAgent;
import io.matedata.identity.AuthorizationFingerprint;
import io.matedata.identity.DatasetGrant;
import io.matedata.identity.application.DataAccessService;
import io.matedata.semantic.*;
import io.matedata.shared.ApplicationException;
import io.matedata.shared.ApplicationException.Kind;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

@Service
public class QueryService {
  private final ModelRepository models;
  private final RunRepository runs;
  private final QueryAgent agent;
  private final QueryExecutor executor;
  private final Semaphore slots = new Semaphore(8);
  private final DataAccessService access;

  public QueryService(
      ModelRepository models,
      RunRepository runs,
      QueryAgent agent,
      QueryExecutor executor,
      DataAccessService access) {
    this.models = models;
    this.runs = runs;
    this.agent = agent;
    this.executor = executor;
    this.access = access;
  }

  /** A query that passed authorization, compilation, SQL verification and execution. */
  private record Executed(QueryPlan plan, CompiledQuery query, QueryExecutor.Result result) {}

  public QueryRun ask(
      String user, String question, String datasetId, String mode, String conversationId) {
    if (question == null || question.isBlank() || question.length() > 2000)
      throw new IllegalArgumentException("问题长度需为 1–2000 字符");
    if (!Set.of("demo", "agent").contains(mode == null ? "" : mode))
      throw new IllegalArgumentException("请选择 demo 或 agent 模式");
    if (conversationId != null && !conversationId.matches("[A-Za-z0-9-]{1,64}"))
      throw new IllegalArgumentException("会话标识不合法");
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
    var steps = new java.util.concurrent.CopyOnWriteArrayList<QueryRun.Step>();
    var sql = new AtomicReference<>("");
    var active = new AtomicReference<>("语义解析");
    try {
      steps.add(new QueryRun.Step("语义检索", "SUCCEEDED", "数据集：" + model.name() + "；仅开放登记的指标与维度", 0));
      Executed executed;
      String answer;
      if (mode.equals("demo")) {
        long t = System.nanoTime();
        QueryPlan requested = new DemoPlanner().plan(question, permittedModel);
        steps.add(new QueryRun.Step("语义解析", "SUCCEEDED", "确定性演示解析（无模型调用）", elapsed(t)));
        executed =
            governed(user, datasetId, model, grant, fingerprint, requested, steps, active, sql);
        answer = summary(model, executed);
      } else {
        var holder = new AtomicReference<Executed>();
        var turn =
            new QueryAgent.Turn(
                question, permittedModel, user, id, sessionKey(user, conversation, fingerprint));
        QueryAgent.GovernedQuery query =
            requested -> {
              if (holder.get() != null) throw new IllegalArgumentException("本问题已完成查询");
              try {
                var done =
                    governed(
                        user, datasetId, model, grant, fingerprint, requested, steps, active, sql);
                holder.set(done);
                return new QueryAgent.Rows(
                    done.result().columns(), done.result().rows(), !grant.rowFilters().isEmpty());
              } catch (RuntimeException e) {
                throw e;
              } catch (Exception e) {
                throw new IllegalStateException("受治理查询执行失败", e);
              }
            };
        active.set("智能体规划");
        var reply =
            agent.answer(
                turn,
                query,
                step ->
                    steps.add(
                        new QueryRun.Step(
                            step.name(), step.status(), step.detail(), step.durationMs())));
        executed = holder.get();
        if (executed == null) {
          if (reply.answer().isBlank())
            throw new IllegalArgumentException("智能体未执行查询，请明确要分析的指标和维度后重试");
          var run =
              new QueryRun(
                      id,
                      conversation,
                      question,
                      datasetId,
                      mode,
                      "NEEDS_INPUT",
                      "",
                      List.of(),
                      List.of(),
                      0,
                      elapsed(start),
                      created,
                      reply.answer(),
                      null,
                      steps)
                  .withScope(fingerprint);
          runs.save(user, run);
          return run;
        }
        answer = reply.answer().isBlank() ? summary(model, executed) : reply.answer();
      }
      var result = executed.result();
      var run =
          new QueryRun(
                  id,
                  conversation,
                  question,
                  datasetId,
                  mode,
                  "SUCCEEDED",
                  sql.get(),
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
      // Validation and authorization messages are user-facing; anything else may leak internals.
      String error =
          e instanceof IllegalArgumentException || e instanceof ApplicationException
              ? e.getMessage()
              : "查询执行失败，请检查数据源、语义映射或模型连接";
      steps.add(new QueryRun.Step(active.get(), "FAILED", error, 0));
      var run =
          new QueryRun(
                  id,
                  conversation,
                  question,
                  datasetId,
                  mode,
                  "FAILED",
                  sql.get(),
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

  /** The single path from a requested plan to rows; the agent reaches data only through here. */
  private Executed governed(
      String user,
      String datasetId,
      SemanticModel model,
      DatasetGrant grant,
      String fingerprint,
      QueryPlan requested,
      List<QueryRun.Step> steps,
      AtomicReference<String> active,
      AtomicReference<String> sql)
      throws Exception {
    active.set("数据权限");
    QueryPlan plan = access.constrain(model, grant, requested);
    steps.add(
        new QueryRun.Step(
            "数据权限", "SUCCEEDED", "指标和维度权限已校验；强制行级过滤 " + grant.rowFilters().size() + " 项", 0));
    active.set("SQL 校验");
    long t = System.nanoTime();
    var compiled = new SemanticCompiler().compile(model, plan);
    new SqlGuard().verify(compiled, model, plan);
    sql.set(compiled.sql());
    steps.add(
        new QueryRun.Step(
            "SQL 校验",
            "SUCCEEDED",
            "AST 校验 + 语义计划一致性校验；只读、参数绑定、最多 " + plan.limit() + " 行",
            elapsed(t)));
    verifyScope(user, datasetId, fingerprint);
    active.set("执行查询");
    t = System.nanoTime();
    var result = executor.execute(model, plan, compiled);
    verifyScope(user, datasetId, fingerprint);
    steps.add(
        new QueryRun.Step(
            "执行查询", "SUCCEEDED", "返回 " + result.rows().size() + " 行；语句执行超时 10 秒；分批读取", elapsed(t)));
    return new Executed(plan, compiled, result);
  }

  private static String summary(SemanticModel model, Executed executed) {
    return "已基于「"
        + model.name()
        + "」计算"
        + model.metric(executed.plan().metric()).name()
        + "，共返回 "
        + executed.result().rows().size()
        + " 行。";
  }

  /**
   * Agent memory is keyed by user, conversation and authorization scope: a follow-up keeps its
   * context only while the semantic model and the user's grant are unchanged.
   */
  static String sessionKey(String user, String conversation, String fingerprint) {
    try {
      var digest =
          MessageDigest.getInstance("SHA-256")
              .digest(
                  (user + "\n" + conversation + "\n" + fingerprint)
                      .getBytes(StandardCharsets.UTF_8));
      return "c" + HexFormat.of().formatHex(digest, 0, 16);
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
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
