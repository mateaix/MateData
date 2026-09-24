package io.matedata.semantic;
import java.util.*;
public interface ModelRepository {
    Optional<SemanticModel> find(String id);
    List<SemanticModel> all();
    void save(SemanticModel model);
}
