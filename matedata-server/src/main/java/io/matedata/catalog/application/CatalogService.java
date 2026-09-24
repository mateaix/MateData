package io.matedata.catalog.application;
import io.matedata.catalog.domain.*;
import io.matedata.catalog.infrastructure.BusinessConnections;
import io.matedata.shared.infrastructure.SecretVault;
import io.matedata.shared.interfaces.ApiException;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

@Service
public class CatalogService {
    private final SourceRepository repository;
    private final SecretVault vault;
    private final BusinessConnections connections;
    public CatalogService(SourceRepository repository,SecretVault vault,BusinessConnections connections) {
        this.repository=repository;this.vault=vault;this.connections=connections;
        if(repository.find("demo_sales").isEmpty())repository.save(new DataSourceDefinition("demo_sales","销售演示数据库","DEMO","jdbc:h2:mem:matedata_sales;DB_CLOSE_DELAY=-1","sa","","AVAILABLE",Instant.now().toString()));
    }
    public List<DataSourceDefinition.View> all(){return repository.all().stream().map(DataSourceDefinition::view).toList();}
    public DataSourceDefinition require(String id){return repository.find(id).orElseThrow(()->new ApiException(404,"NOT_FOUND","数据源不存在"));}
    public DataSourceDefinition.View create(String name,String type,String url,String username,String password) {
        ConnectionPolicy.validate(type,url);
        if(name==null||name.isBlank()||name.length()>100||username==null||username.length()>100||password==null||password.length()>1024)throw new IllegalArgumentException("数据源名称与凭证不合法");
        var source=new DataSourceDefinition("s_"+UUID.randomUUID().toString().replace("-",""),name,type,url,username,vault.encrypt(password),"UNTESTED",Instant.now().toString());repository.save(source);return source.view();
    }
    public Map<String,Object> test(String id) {
        var source=require(id);boolean ok;
        try(var c=connections.open(source)){ok=c.isValid(5);}catch(Exception e){ok=false;}
        repository.save(new DataSourceDefinition(source.id(),source.name(),source.type(),source.jdbcUrl(),source.username(),source.encryptedPassword(),ok?"AVAILABLE":"ERROR",source.createdAt()));
        return Map.of("success",ok,"message",ok?"连接成功":"连接失败，请检查地址、只读账号与网络配置");
    }
    public record Column(String name,String type){}
    public record Table(String name,List<Column> columns){}
    public List<Table> tables(String id) {
        var source=require(id);var tables=new ArrayList<Table>();
        try(var c=connections.open(source)) {
            var meta=c.getMetaData();String schema=source.type().equals("DEMO")?"PUBLIC":c.getSchema();
            try(var r=meta.getTables(c.getCatalog(),schema,"%",new String[]{"TABLE"})) {
                while(r.next()&&tables.size()<100){String table=r.getString("TABLE_NAME");var columns=new ArrayList<Column>();
                    try(var cr=meta.getColumns(c.getCatalog(),schema,table,"%")){while(cr.next())columns.add(new Column(cr.getString("COLUMN_NAME"),cr.getString("TYPE_NAME")));}
                    tables.add(new Table(table,columns));
                }
            }return tables;
        }catch(Exception e){throw new ApiException(422,"CONNECTION_FAILED","无法读取元数据，请先验证数据源连接");}
    }
}
