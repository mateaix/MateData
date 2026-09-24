package io.matedata.identity.application;

import io.matedata.shared.infrastructure.DocumentStore;
import io.matedata.shared.interfaces.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.util.*;

@Service
public class IdentityService {
    public record User(String username,String displayName,String role) {}
    public record Account(String username,String displayName,String role,String passwordHash) { public User publicView(){return new User(username,displayName,role);} }
    private final DocumentStore store;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);
    public IdentityService(DocumentStore store,@Value("${matedata.admin-password:}") String password) {
        this.store=store;
        if(store.get("identity","admin",Account.class).isEmpty()) {
            if(password.isBlank()) { password=UUID.randomUUID().toString(); System.out.println("MateData first-run admin password: "+password); }
            create("admin","管理员","ADMIN",password);
        }
    }
    public User login(String username,String password) {
        var account=store.get("identity",username==null?"":username,Account.class);
        if(account.isEmpty() || password==null || !encoder.matches(password,account.get().passwordHash())) throw new ApiException(401,"INVALID_CREDENTIALS","用户名或密码错误");
        return account.get().publicView();
    }
    public User create(String username,String name,String role,String password) {
        if(username==null || !username.matches("[a-zA-Z][a-zA-Z0-9_]{2,40}") || name==null || name.isBlank()) throw new IllegalArgumentException("用户名或显示名称不合法");
        if(!Set.of("ADMIN","ANALYST","VIEWER").contains(role)) throw new IllegalArgumentException("角色不合法");
        if(password==null || password.length()<12 || password.length()>72) throw new IllegalArgumentException("密码长度必须为 12–72 字符");
        if(store.get("identity",username,Account.class).isPresent()) throw new ApiException(409,"CONFLICT","用户名已存在");
        var a=new Account(username,name,role,encoder.encode(password));store.save("identity",username,a);return a.publicView();
    }
    public List<User> users(){return store.list("identity",Account.class).stream().map(Account::publicView).toList();}
}
