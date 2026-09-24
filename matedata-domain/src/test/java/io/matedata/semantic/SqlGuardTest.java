package io.matedata.semantic;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class SqlGuardTest {
    private final SqlGuard guard = new SqlGuard();
    @Test void acceptsOnlyExactlyCompiledPlans() {
        var model = SemanticModel.sales(); var plan = new QueryPlan("revenue","region",Map.of(),10);
        assertThatCode(() -> guard.verify(new SemanticCompiler().compile(model,plan),model,plan)).doesNotThrowAnyException();
    }
    @Test void rejectsAnySqlThatEscapesTheSemanticCompiler() {
        var model = SemanticModel.sales(); var plan = new QueryPlan("revenue","region",Map.of(),10);
        for (var sql : List.of("DELETE FROM sales", "SELECT * FROM sales; DROP TABLE sales", "SELECT password FROM users", "SELECT FILE_READ('/etc/passwd') FROM sales", "SELECT * FROM (SELECT * FROM users) x", "SELECT * FROM sales UNION SELECT * FROM users")) {
            assertThatThrownBy(() -> guard.verify(new CompiledQuery(sql,List.of()),model,plan)).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void rejectsChangedParametersEvenForValidSql() {
        var model = SemanticModel.sales(); var plan = new QueryPlan("revenue",null,Map.of("region","华东"),10);
        var compiled = new SemanticCompiler().compile(model,plan);
        assertThatThrownBy(() -> guard.verify(new CompiledQuery(compiled.sql(), List.of("华南")),model,plan)).isInstanceOf(IllegalArgumentException.class);
    }
}
