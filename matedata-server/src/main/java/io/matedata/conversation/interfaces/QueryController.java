package io.matedata.conversation.interfaces;
import io.matedata.conversation.*;
import io.matedata.conversation.application.QueryService;
import io.matedata.identity.interfaces.Access;
import io.matedata.harness.application.AuditService;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
@RestController @RequestMapping("/api/v1")
public class QueryController {
    private final QueryService queries;private final AuditService audit;
    public QueryController(QueryService queries,AuditService audit){this.queries=queries;this.audit=audit;}
    public record Ask(String question,String datasetId,String mode,String conversationId){}
    @PostMapping("/queries") public QueryRun ask(@RequestBody Ask ask,HttpServletRequest request){var u=Access.analyst(request);var run=queries.ask(u.username(),ask.question(),ask.datasetId(),ask.mode(),ask.conversationId());audit.record(u.username(),"QUERY_"+run.status(),run.id());return run;}
    @GetMapping("/runs") public List<QueryRun> history(HttpServletRequest request){return queries.history(Access.user(request).username());}
    @GetMapping("/runs/{id}") public QueryRun get(@PathVariable String id,HttpServletRequest request){return queries.get(Access.user(request).username(),id);}
}
