package io.matedata.conversation.infrastructure;
import io.matedata.catalog.application.CatalogService;
import io.matedata.catalog.infrastructure.BusinessConnections;
import io.matedata.semantic.*;
import org.springframework.stereotype.Component;
import java.util.*;
import java.sql.*;

@Component
public class JdbcQueryExecutor {
    public record Result(List<String> columns,List<Map<String,Object>> rows){}
    private final CatalogService catalog;private final BusinessConnections connections;
    public JdbcQueryExecutor(CatalogService catalog,BusinessConnections connections){this.catalog=catalog;this.connections=connections;}
    public Result execute(SemanticModel model,QueryPlan plan,CompiledQuery query)throws SQLException {
        new SqlGuard().verify(query,model,plan);
        try(var c=connections.open(catalog.require(model.sourceId()));var s=c.prepareStatement(query.sql())) {
            s.setQueryTimeout(10);s.setMaxRows(Math.min(plan.limit(),1000));
            for(int i=0;i<query.parameters().size();i++)s.setObject(i+1,query.parameters().get(i));
            try(var rs=s.executeQuery()) {
                var md=rs.getMetaData();var columns=new ArrayList<String>();var rows=new ArrayList<Map<String,Object>>();
                for(int i=1;i<=md.getColumnCount();i++)columns.add(md.getColumnLabel(i).toLowerCase(Locale.ROOT));
                while(rs.next()&&rows.size()<1000){var row=new LinkedHashMap<String,Object>();for(int i=1;i<=columns.size();i++)row.put(columns.get(i-1),rs.getObject(i));rows.add(row);}
                return new Result(columns,rows);
            }
        }
    }
}
