package io.matedata.harness.interfaces;
import io.matedata.conversation.application.QueryService;
import io.matedata.identity.interfaces.Access;
import io.matedata.shared.infrastructure.DocumentStore;
import io.matedata.harness.application.AuditService;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

@RestController @RequestMapping("/api/v1/evaluations")
public class EvaluationController {
    public record Case(String id,String name,String question,String datasetId,String expectedMetric,String expectedDimension){}
    public record Result(String name,boolean passed,String message,String runId){}
    public record Report(String id,long passed,int total,long durationMs,List<Result> results){}
    public record Request(String mode){}
    private final QueryService queries;private final DocumentStore store;private final AuditService audit;
    public EvaluationController(QueryService queries,DocumentStore store,AuditService audit){this.queries=queries;this.store=store;this.audit=audit;}
    @GetMapping public List<Case> cases(){return List.of(new Case("region_revenue","区域销售额","各区域销售额","sales","revenue","region"),new Case("category_profit","品类利润","各品类利润","sales","profit","category"),new Case("month_revenue","销售趋势","每月销售额趋势","sales","revenue","month"),new Case("channel_orders","渠道订单量","各渠道订单数","sales","orders","channel"));}
    @PostMapping("/run") public Report run(@RequestBody Request body,HttpServletRequest request){
        var u=Access.analyst(request);long start=System.nanoTime();var results=new ArrayList<Result>();
        for(var test:cases()) {var run=queries.ask(u.username(),test.question(),test.datasetId(),body.mode(),null);
            boolean pass=run.status().equals("SUCCEEDED")&&run.columns().contains(test.expectedMetric())&&run.columns().contains(test.expectedDimension())&&!run.rows().isEmpty();
            results.add(new Result(test.name(),pass,pass?"结果包含预期指标、维度且返回真实数据":Objects.toString(run.error(),"结果与预期不一致"),run.id()));
        }
        var report=new Report(UUID.randomUUID().toString(),results.stream().filter(Result::passed).count(),results.size(),(System.nanoTime()-start)/1_000_000,results);
        store.save("evaluations_"+u.username(),report.id(),report);audit.record(u.username(),"EVALUATION_RUN",report.id());return report;
    }
}
