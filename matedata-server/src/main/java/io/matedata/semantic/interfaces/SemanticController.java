package io.matedata.semantic.interfaces;
import io.matedata.semantic.*;
import io.matedata.catalog.application.CatalogService;
import io.matedata.identity.interfaces.Access;
import io.matedata.harness.application.AuditService;
import io.matedata.shared.interfaces.ApiException;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

@RestController @RequestMapping("/api/v1/datasets")
public class SemanticController {
    private final ModelRepository models;private final CatalogService catalog;private final AuditService audit;
    public SemanticController(ModelRepository models,CatalogService catalog,AuditService audit){this.models=models;this.catalog=catalog;this.audit=audit;}
    @GetMapping public List<SemanticModel> list(){return models.all();}
    @PostMapping public SemanticModel create(@RequestBody SemanticModel model,HttpServletRequest request){
        var u=Access.admin(request);if(models.find(model.id()).isPresent())throw new ApiException(409,"CONFLICT","数据集标识已存在");validate(model);models.save(model);audit.record(u.username(),"DATASET_CREATE",model.id());return model;
    }
    @PutMapping("/{id}") public SemanticModel update(@PathVariable String id,@RequestBody SemanticModel model,HttpServletRequest request){
        var u=Access.admin(request);if(!id.equals(model.id()))throw new IllegalArgumentException("数据集标识不一致");if(models.find(id).isEmpty())throw new ApiException(404,"NOT_FOUND","数据集不存在");validate(model);models.save(model);audit.record(u.username(),"DATASET_UPDATE",id);return model;
    }
    private void validate(SemanticModel model){
        catalog.require(model.sourceId());
        var table=catalog.tables(model.sourceId()).stream().filter(t->t.name().equalsIgnoreCase(model.tableName())).findFirst().orElseThrow(()->new IllegalArgumentException("物理表不存在或无法访问"));
        var columns=table.columns().stream().map(c->c.name().toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
        if(model.metrics().stream().anyMatch(m->!columns.contains(m.column().toLowerCase(Locale.ROOT)))||model.dimensions().stream().anyMatch(d->!columns.contains(d.column().toLowerCase(Locale.ROOT))))throw new IllegalArgumentException("语义字段引用了不存在的物理列");
    }
}
