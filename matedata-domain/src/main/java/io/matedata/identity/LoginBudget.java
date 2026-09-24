package io.matedata.identity;

import java.time.Clock;
import java.util.*;

/** Bounded, single-process admission policy for costly password checks. */
public final class LoginBudget {
  private final Clock clock;
  private final Map<String, Window> attempts = new HashMap<>();

  private record Window(long started, int count) {}

  public LoginBudget(Clock clock) {
    this.clock = clock;
  }

  public synchronized boolean acquire(String address) {
    long now = clock.millis();
    attempts.entrySet().removeIf(entry -> now - entry.getValue().started() >= 60_000);
    var window = attempts.get(address);
    if (window == null) {
      if (attempts.size() >= 10_000) return false;
      attempts.put(address, new Window(now, 1));
      return true;
    }
    if (window.count() >= 10) return false;
    attempts.put(address, new Window(window.started(), window.count() + 1));
    return true;
  }
}
