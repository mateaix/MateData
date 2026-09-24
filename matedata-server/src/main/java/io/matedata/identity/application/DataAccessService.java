package io.matedata.identity.application;

import io.matedata.identity.*;
import io.matedata.semantic.*;
import io.matedata.shared.ApplicationException;
import io.matedata.shared.ApplicationException.Kind;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class DataAccessService {
  private final AccountRepository accounts;
  private final GrantRepository grants;
  private final ModelRepository models;

  public DataAccessService(
      AccountRepository accounts, GrantRepository grants, ModelRepository models) {
    this.accounts = accounts;
    this.grants = grants;
    this.models = models;
  }

  public DatasetGrant require(String user, SemanticModel model) {
    var account =
        accounts
            .find(user)
            .orElseThrow(() -> new ApplicationException(Kind.UNAUTHENTICATED, "账号不存在"));
    if (account.role().equals("ADMIN")) return full(user, model);
    var grant =
        grants
            .find(user, model.id())
            .orElseGet(
                () ->
                    model.id().equals("sales")
                            && model.sourceId().equals("demo_sales")
                            && model.tableName().equalsIgnoreCase("sales")
                        ? full(user, model)
                        : null);
    if (grant == null || !grant.enabled())
      throw new ApplicationException(Kind.FORBIDDEN, "没有此数据集的访问权限");
    return grant;
  }

  private DatasetGrant full(String user, SemanticModel model) {
    return new DatasetGrant(
        user,
        model.id(),
        true,
        model.metrics().stream().map(SemanticModel.Metric::id).toList(),
        model.dimensions().stream().map(SemanticModel.Dimension::id).toList(),
        Map.of());
  }

  public List<SemanticModel> visible(String user) {
    var visible = new ArrayList<SemanticModel>();
    for (var model : models.all())
      try {
        visible.add(restrict(model, require(user, model)));
      } catch (ApplicationException ignored) {
      }
    return visible;
  }

  public SemanticModel restrict(SemanticModel model, DatasetGrant grant) {
    var metrics = model.metrics().stream().filter(m -> grant.metrics().contains(m.id())).toList();
    if (metrics.isEmpty()) throw new ApplicationException(Kind.FORBIDDEN, "此数据集没有可访问指标");
    return new SemanticModel(
        model.id(),
        model.name(),
        model.description(),
        model.sourceId(),
        model.tableName(),
        metrics,
        model.dimensions().stream().filter(d -> grant.dimensions().contains(d.id())).toList(),
        model.dialect());
  }

  public QueryPlan constrain(SemanticModel full, DatasetGrant grant, QueryPlan requested) {
    new SemanticCompiler().compile(restrict(full, grant), requested);
    var filters = new HashMap<>(requested.filters());
    grant
        .rowFilters()
        .forEach(
            (dimension, value) -> {
              if (filters.containsKey(dimension) && !filters.get(dimension).equals(value))
                throw new ApplicationException(Kind.FORBIDDEN, "查询筛选与行级授权冲突");
              filters.put(dimension, value);
            });
    return new QueryPlan(requested.metric(), requested.dimension(), filters, requested.limit());
  }

  public List<DatasetGrant> all() {
    return grants.all();
  }

  public DatasetGrant save(DatasetGrant grant) {
    if (accounts.find(grant.username()).isEmpty()) throw new IllegalArgumentException("用户不存在");
    var model =
        models.find(grant.datasetId()).orElseThrow(() -> new IllegalArgumentException("数据集不存在"));
    if (grant.enabled() && grant.metrics().isEmpty())
      throw new IllegalArgumentException("至少授权一个指标");
    grant.metrics().forEach(model::metric);
    grant.dimensions().forEach(model::dimension);
    grant
        .rowFilters()
        .forEach(
            (key, value) -> {
              model.dimension(key).valueType().parse(value);
            });
    grants.save(grant);
    return grant;
  }
}
