package io.matedata.identity.interfaces;

import io.matedata.harness.application.AuditService;
import io.matedata.identity.LoginBudget;
import io.matedata.identity.application.IdentityService;
import io.matedata.shared.ApplicationException;
import io.matedata.shared.ApplicationException.Kind;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final IdentityService service;
  private final AuditService audit;
  private final LoginBudget budget = new LoginBudget(Clock.systemUTC());
  private final Semaphore passwordChecks = new Semaphore(4);

  public AuthController(IdentityService service, AuditService audit) {
    this.service = service;
    this.audit = audit;
  }

  public record Login(String username, String password) {}

  @PostMapping("/login")
  public IdentityService.User login(@RequestBody Login body, HttpServletRequest request) {
    String principal = body.username() == null ? "" : body.username();
    if (principal.length() > 128
        || !budget.acquire(request.getRemoteAddr())
        || !passwordChecks.tryAcquire())
      throw new ApplicationException(Kind.BUSY, "登录请求过于频繁，请稍后重试");
    try {
      var user = service.login(principal, body.password());
      var old = request.getSession(false);
      if (old != null) old.invalidate();
      request.getSession(true).setAttribute("user", user);
      audit.record(user.username(), "AUTH_LOGIN", "session");
      return user;
    } catch (ApplicationException failure) {
      audit.record(principal, "AUTH_LOGIN_FAILED", "session");
      throw failure;
    } finally {
      passwordChecks.release();
    }
  }

  @GetMapping("/me")
  public IdentityService.User me(HttpServletRequest request) {
    return Access.user(request);
  }

  @PostMapping("/logout")
  public Map<String, Boolean> logout(HttpServletRequest request) {
    var user = Access.user(request);
    request.getSession().invalidate();
    audit.record(user.username(), "AUTH_LOGOUT", "session");
    return Map.of("success", true);
  }
}
