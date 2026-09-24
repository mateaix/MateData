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

  public QueryController(QueryService queries, AuditService audit) {
    this.queries = queries;
    this.audit = audit;
  }

  public record Ask(String question, String datasetId, String mode, String conversationId) {}

  @PostMapping("/queries")
  public QueryRun ask(@RequestBody Ask ask, HttpServletRequest request) {
    var u = Access.analyst(request);
    var run =
        queries.ask(
            u.username(), ask.question(), ask.datasetId(), ask.mode(), ask.conversationId());
    audit.record(u.username(), "QUERY_" + run.status(), run.id());
    return RunResponses.safeNumbers(run);
  }

  @GetMapping("/runs")
  public List<QueryRun> history(
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "50") int limit,
      HttpServletRequest request) {
    return queries.history(Access.user(request).username(), offset, limit);
  }

  @GetMapping("/runs/page")
  public RunPage page(
      @RequestParam(defaultValue = "0") int offset,
      @RequestParam(defaultValue = "50") int limit,
      HttpServletRequest request) {
    return queries.historyPage(Access.user(request).username(), offset, limit);
  }

  @GetMapping("/runs/{id}")
  public QueryRun get(@PathVariable String id, HttpServletRequest request) {
    return RunResponses.safeNumbers(queries.get(Access.user(request).username(), id));
  }
}
