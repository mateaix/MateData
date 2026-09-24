package io.matedata.conversation.infrastructure;
import io.matedata.conversation.*;
import io.matedata.shared.infrastructure.DocumentStore;
import org.springframework.stereotype.Repository;
import java.util.*;
@Repository public class JdbcRunRepository implements RunRepository {
    private final DocumentStore store;
    public JdbcRunRepository(DocumentStore store){this.store=store;}
    public void save(String owner,QueryRun run){store.save("runs_"+owner,run.id(),run);}
    public Optional<QueryRun> find(String owner,String id){return store.get("runs_"+owner,id,QueryRun.class);}
    public List<QueryRun> all(String owner){return store.list("runs_"+owner,QueryRun.class);}
}
