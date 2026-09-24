package io.matedata.identity;

import static org.assertj.core.api.Assertions.*;

import java.time.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LoginBudgetTest {
  @Test
  void capsAttemptsAndExpiresWindow() {
    var now = new AtomicReference<>(Instant.parse("2026-09-24T00:00:00Z"));
    var clock =
        new Clock() {
          public ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          public Clock withZone(ZoneId zone) {
            return this;
          }

          public Instant instant() {
            return now.get();
          }
        };
    var budget = new LoginBudget(clock);
    for (int i = 0; i < 10; i++) assertThat(budget.acquire("127.0.0.1")).isTrue();
    assertThat(budget.acquire("127.0.0.1")).isFalse();
    assertThat(budget.acquire("127.0.0.2")).isTrue();
    now.set(now.get().plusSeconds(61));
    assertThat(budget.acquire("127.0.0.1")).isTrue();
  }
}
