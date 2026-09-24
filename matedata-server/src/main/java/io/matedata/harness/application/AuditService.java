package io.matedata.harness.application;
import io.matedata.shared.infrastructure.DocumentStore;
import org.springframework.stereotype.Service;
import java.util.*;
import java.time.Instant;
@Service public class AuditService {
    public record Entry(String id,String username,String action,String resource,String createdAt){}
    private final DocumentStore store;
    public AuditService(DocumentStore store){this.store=store;}
    public void record(String user,String action,String resource){String id=UUID.randomUUID().toString();store.save("audit",id,new Entry(id,user,action,resource,Instant.now().toString()));}
    public List<Entry> all(){return store.list("audit",Entry.class);}
}
