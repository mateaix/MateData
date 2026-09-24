package io.matedata.conversation.application;

import io.matedata.semantic.*;
import io.matedata.harness.QueryPlanner;
import io.matedata.conversation.*;
import io.matedata.conversation.infrastructure.JdbcQueryExecutor;
import io.matedata.shared.interfaces.ApiException;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;

@Service
public class QueryService {
    private final ModelRepository models;private final RunRepository runs;private final QueryPlanner agent;private final JdbcQueryExecutor executor;
    private final Semaphore slots = new Semaphore(8);
    public QueryService(ModelRepository models,RunRepository runs,QueryPlanner agent,JdbcQueryExecutor executor){this.models=models;this.runs=runs;this.agent=agent;this.executor=executor;}
    public QueryRun ask(String user,String question,String datasetId,String mode,String conversationId) {
        if(question==null||question.isBlank()||question.length()>2000)throw new IllegalArgumentException("问题长度需为 1–2000 字符");
        if(!Set.of("demo","agent").contains(mode==null?"":mode))throw new IllegalArgumentException("请选择 demo 或 agent 模式");
        var model=models.find(datasetId).orElseThrow(()->new ApiException(404,"NOT_FOUND","数据集不存在"));
        if(!slots.tryAcquire())throw new ApiException(429,"BUSY","并发任务已满，请稍后重试");
        long start=System.nanoTime();String id=UUID.randomUUID().toString(),created=Instant.now().toString();
        String conversation=conversationId==null?UUID.randomUUID().toString():conversationId;
        if(conversation.length()>64){slots.release();throw new IllegalArgumentException("会话标识过长");}
        var steps=new ArrayList<QueryRun.Step>();String sql="";String active="语义解析";
        try {
            steps.add(new QueryRun.Step("语义检索","SUCCEEDED","数据集："+model.name()+"；仅开放登记的指标与维度",0));
            long t=System.nanoTime();QueryPlan plan=mode.equals("demo")?new DemoPlanner().plan(question,model):agent.plan(question,model,user,id);
            steps.add(new QueryRun.Step(active,"SUCCEEDED",mode.equals("demo")?"确定性演示解析（无模型调用）":"AgentScope Harness 生成受控语义计划",elapsed(t)));
            active="SQL 校验";t=System.nanoTime();var compiled=new SemanticCompiler().compile(model,plan);new SqlGuard().verify(compiled,model,plan);sql=compiled.sql();
            steps.add(new QueryRun.Step(active,"SUCCEEDED","AST 校验 + 语义计划一致性校验；只读、参数绑定、最多 "+plan.limit()+" 行",elapsed(t)));
            active="执行查询";t=System.nanoTime();var result=executor.execute(model,plan,compiled);
            steps.add(new QueryRun.Step(active,"SUCCEEDED","返回 "+result.rows().size()+" 行；查询超时 10 秒",elapsed(t)));
            String answer="已基于「"+model.name()+"」计算"+model.metric(plan.metric()).name()+"，共返回 "+result.rows().size()+" 行。";
            var run=new QueryRun(id,conversation,question,datasetId,mode,"SUCCEEDED",sql,result.columns(),result.rows(),result.rows().size(),elapsed(start),created,answer,null,steps);runs.save(user,run);return run;
        }catch(Exception e) {
            String error=e instanceof IllegalArgumentException?e.getMessage():"查询执行失败，请检查数据源、语义映射或模型连接";
            steps.add(new QueryRun.Step(active,"FAILED",error,0));
            var run=new QueryRun(id,conversation,question,datasetId,mode,"FAILED",sql,List.of(),List.of(),0,elapsed(start),created,"",error,steps);runs.save(user,run);return run;
        }finally{slots.release();}
    }
    public List<QueryRun> history(String user){return runs.all(user);}
    public QueryRun get(String user,String id){return runs.find(user,id).orElseThrow(()->new ApiException(404,"NOT_FOUND","运行记录不存在"));}
    private static long elapsed(long start){return (System.nanoTime()-start)/1_000_000;}
}
