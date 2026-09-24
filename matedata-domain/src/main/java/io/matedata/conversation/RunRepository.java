package io.matedata.conversation;
import java.util.*;
public interface RunRepository {
    void save(String owner,QueryRun run);
    Optional<QueryRun> find(String owner,String id);
    List<QueryRun> all(String owner);
}
