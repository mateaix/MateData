package io.matedata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "MATEDATA_TEST_MYSQL_URL", matches = ".+")
class ExternalMysqlTest extends ExternalDatabaseContract {
  @Test
  void supportsRealMysql() throws Exception {
    verifyDatabase("MYSQL", "MYSQL");
  }
}
