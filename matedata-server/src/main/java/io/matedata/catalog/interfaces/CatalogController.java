package io.matedata.catalog.interfaces;
import io.matedata.catalog.application.CatalogService;
import io.matedata.catalog.domain.DataSourceDefinition;
import io.matedata.identity.interfaces.Access;
import io.matedata.harness.application.AuditService;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
@RestController @RequestMapping("/api/v1/sources")
public class CatalogController {
    private final CatalogService catalog;private final AuditService audit;
    public CatalogController(CatalogService catalog,AuditService audit){this.catalog=catalog;this.audit=audit;}
    public record Create(String name,String type,String jdbcUrl,String username,String password){}
    @GetMapping public List<DataSourceDefinition.View> list(HttpServletRequest request){Access.admin(request);return catalog.all();}
    @PostMapping public DataSourceDefinition.View create(@RequestBody Create body,HttpServletRequest request){var u=Access.admin(request);var s=catalog.create(body.name(),body.type(),body.jdbcUrl(),body.username(),body.password());audit.record(u.username(),"SOURCE_CREATE",s.id());return s;}
    @PostMapping("/{id}/test") public Map<String,Object> test(@PathVariable String id,HttpServletRequest request){var u=Access.admin(request);var r=catalog.test(id);audit.record(u.username(),"SOURCE_TEST",id);return r;}
    @GetMapping("/{id}/tables") public List<CatalogService.Table> tables(@PathVariable String id,HttpServletRequest request){Access.admin(request);return catalog.tables(id);}
}
