package io.matedata.harness.interfaces;

import io.matedata.harness.application.*;
import io.matedata.identity.application.IdentityService;
import io.matedata.identity.interfaces.Access;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class PlatformController {
  private final ModelSettings settings;
  private final AuditService audit;
  private final IdentityService identity;

  public PlatformController(ModelSettings settings, AuditService audit, IdentityService identity) {
    this.settings = settings;
    this.audit = audit;
    this.identity = identity;
  }

  @GetMapping("/system")
  public Map<String, Object> system() {
    return Map.of(
        "name",
        "MateData",
        "version",
        "0.1.0",
        "javaVersion",
        System.getProperty("java.version"),
        "agentScopeVersion",
        "2.0.3",
        "mode",
        settings.configured() ? "agent" : "demo",
        "modelConfigured",
        settings.configured());
  }

  @GetMapping("/settings/model")
  public Map<String, Object> model(HttpServletRequest request) {
    Access.admin(request);
    return settings.view();
  }

  public record Settings(
      String baseUrl, String model, String apiKey, int maxSteps, int timeoutSeconds) {}

  @PutMapping("/settings/model")
  public Map<String, Object> save(@RequestBody Settings body, HttpServletRequest request) {
    var u = Access.admin(request);
    var r =
        settings.save(
            body.baseUrl(), body.model(), body.apiKey(), body.maxSteps(), body.timeoutSeconds());
    audit.record(u.username(), "MODEL_SETTINGS_UPDATE", body.model());
    return r;
  }

  @GetMapping("/audit")
  public List<io.matedata.harness.AuditEntry> audit(HttpServletRequest request) {
    Access.admin(request);
    return audit.all();
  }

  @GetMapping("/users")
  public List<IdentityService.User> users(HttpServletRequest request) {
    Access.admin(request);
    return identity.users();
  }

  public record CreateUser(String username, String displayName, String role, String password) {}

  @PostMapping("/users")
  public IdentityService.User createUser(@RequestBody CreateUser body, HttpServletRequest request) {
    var u = Access.admin(request);
    var created =
        identity.create(body.username(), body.displayName(), body.role(), body.password());
    audit.record(u.username(), "USER_CREATE", created.username());
    return created;
  }
}
