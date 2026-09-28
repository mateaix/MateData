package io.matedata.conversation.interfaces;

import io.matedata.conversation.*;
import io.matedata.conversation.application.QueryService;
import io.matedata.harness.application.AuditService;
import io.matedata.identity.interfaces.Access;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class QueryController {
  private final QueryService queries;
  private final AuditService audit;
  private final io.matedata.identity.ScopeTokenEncoder scopeTokens;

  public QueryController(
      QueryService queries,
      AuditService audit,
      io.matedata.identity.ScopeTokenEncoder scopeTokens) {
    this.queries = queries;
    this.audit = audit;
    this.scopeTokens = scopeTokens;
  }

  public record Ask(String question, String datasetId, String mode, String conversationId) {}

  @PostMapping("/queries")
  public QueryRun ask(
      @RequestBody Ask ask,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      HttpServletRequest request) {
    var u = Access.analyst(request);
    var run =
        queries.ask(
            u.username(),
            ask.question(),
            ask.datasetId(),
            ask.mode(),
            ask.conversationId(),
            idempotencyKey);
    audit.record(u.username(), "QUERY_" + run.status(), run.id());
    return response(run);
  }

  @GetMapping("/runs")
  public List<QueryRun> history(
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "50") int limit,
      HttpServletRequest request) {
    return queries.history(Access.user(request).username(), offset, limit).stream()
        .map(this::response)
        .toList();
  }

  @GetMapping("/runs/page")
  public RunPage page(
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "50") int limit,
      HttpServletRequest request) {
    var page = queries.historyPage(Access.user(request).username(), offset, limit);
    return new RunPage(page.items().stream().map(this::response).toList(), page.nextOffset());
  }

  @GetMapping("/runs/{id}")
  public QueryRun get(@PathVariable String id, HttpServletRequest request) {
    return response(queries.get(Access.user(request).username(), id));
  }

  private QueryRun response(QueryRun run) {
    return RunResponses.safeNumbers(run)
        .withScope(scopeTokens.encodeFingerprint(run.scopeFingerprint()));
  }
}
