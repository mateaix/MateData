package io.matedata.identity.application;

import io.matedata.identity.*;
import io.matedata.shared.ApplicationException;
import io.matedata.shared.ApplicationException.Kind;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class IdentityService {
  public record User(String username, String displayName, String role) {}

  private final AccountRepository repository;
  private final PasswordHasher encoder;
  private final String dummyPasswordHash;

  public IdentityService(
      AccountRepository repository,
      PasswordHasher encoder,
      @Value("${matedata.admin-password:}") String password) {
    this.repository = repository;
    this.encoder = encoder;
    this.dummyPasswordHash = encoder.encode(UUID.randomUUID().toString());
    if (repository.find("admin").isEmpty()) {
      if (password.isBlank()) {
        password = UUID.randomUUID().toString();
        System.out.println("MateData first-run admin password: " + password);
      }
      create("admin", "管理员", "ADMIN", password);
    }
  }

  public User login(String username, String password) {
    var account = repository.find(username == null ? "" : username);
    boolean valid =
        password != null
            && password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 72
            && encoder.matches(
                password, account.map(Account::passwordHash).orElse(dummyPasswordHash));
    if (account.isEmpty() || !valid)
      throw new ApplicationException(Kind.INVALID_CREDENTIALS, "用户名或密码错误");
    return publicView(account.get());
  }

  public User create(String username, String name, String role, String password) {
    if (username == null
        || !username.matches("[a-zA-Z][a-zA-Z0-9_]{2,40}")
        || name == null
        || name.isBlank()) throw new IllegalArgumentException("用户名或显示名称不合法");
    if (role == null || !Set.of("ADMIN", "ANALYST", "VIEWER").contains(role))
      throw new IllegalArgumentException("角色不合法");
    if (password == null
        || password.length() < 12
        || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
      throw new IllegalArgumentException("密码至少 12 字符且 UTF-8 编码不超过 72 字节");
    if (repository.find(username).isPresent())
      throw new ApplicationException(Kind.CONFLICT, "用户名已存在");
    var a = new Account(username, name, role, encoder.encode(password));
    repository.save(a);
    return publicView(a);
  }

  private static User publicView(Account account) {
    return new User(account.username(), account.displayName(), account.role());
  }

  public List<User> users() {
    return repository.all().stream().map(IdentityService::publicView).toList();
  }
}
