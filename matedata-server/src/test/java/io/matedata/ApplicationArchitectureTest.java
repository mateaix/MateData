package io.matedata;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ApplicationArchitectureTest {
  @Test
  void applicationServicesDoNotDependOnAdapters() throws Exception {
    var violations = new ArrayList<String>();
    try (var paths = Files.walk(Path.of("src/main/java/io/matedata"))) {
      for (var path :
          paths
              .filter(p -> p.toString().contains("/application/") && p.toString().endsWith(".java"))
              .toList()) {
        for (var line : Files.readAllLines(path)) {
          if (line.matches(".*\\bio\\.matedata\\.[\\w.]*\\.(infrastructure|interfaces)\\..*"))
            violations.add(path + ": " + line);
        }
      }
    }
    assertThat(violations)
        .as("Application services depend only on ports, not infrastructure or HTTP adapters")
        .isEmpty();
  }

  @Test
  void domainDoesNotExposeJdbcTypes() throws Exception {
    var violations = new ArrayList<String>();
    try (var paths = Files.walk(Path.of("../matedata-domain/src/main/java"))) {
      for (var path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
        if (Files.readString(path).contains("java.sql.")) violations.add(path.toString());
      }
    }
    assertThat(violations).as("Domain ports must not expose JDBC types").isEmpty();
  }
}
