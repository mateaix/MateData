package io.matedata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "MATEDATA_TEST_POSTGRES_URL", matches = ".+")
class ExternalPostgresTest extends ExternalDatabaseContract {
  @Test
  void supportsRealPostgres() throws Exception {
    verifyDatabase("POSTGRESQL", "POSTGRES");
  }
}
