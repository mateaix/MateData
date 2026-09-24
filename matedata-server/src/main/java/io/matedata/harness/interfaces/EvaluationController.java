package io.matedata.harness.interfaces;

import io.matedata.harness.*;
import io.matedata.harness.application.EvaluationService;
import io.matedata.identity.interfaces.Access;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/evaluations")
public class EvaluationController {
  public record Request(String mode) {}

  private final EvaluationService service;

  public EvaluationController(EvaluationService service) {
    this.service = service;
  }

  @GetMapping
  public List<EvaluationCase> cases() {
    return service.cases();
  }

  @PostMapping("/run")
  public EvaluationReport run(@RequestBody Request body, HttpServletRequest request) {
    return service.run(Access.analyst(request).username(), body.mode());
  }

  @GetMapping("/reports")
  public List<EvaluationReport> reports(HttpServletRequest request) {
    return service.all(Access.user(request).username());
  }

  @GetMapping("/reports/{id}")
  public EvaluationReport report(@PathVariable String id, HttpServletRequest request) {
    return service.get(Access.user(request).username(), id);
  }
}
