package io.matedata.conversation.application;

import io.matedata.shared.ApplicationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/** Single-process coordination; idle entries are removed even after timeout or interruption. */
final class QueryCoordinator {
  private static final class Entry {
    final ReentrantLock lock = new ReentrantLock(true);
    int references;
  }

  private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

  Lease acquire(String key) {
    Entry entry =
        entries.compute(
            key,
            (k, current) -> {
              Entry value = current == null ? new Entry() : current;
              value.references++;
              return value;
            });
    boolean acquired = false;
    try {
      acquired = entry.lock.tryLock(1, TimeUnit.SECONDS);
      if (!acquired) throw busy();
      return () -> {
        entry.lock.unlock();
        release(key, entry);
      };
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw busy();
    } finally {
      if (!acquired) release(key, entry);
    }
  }

  private void release(String key, Entry entry) {
    entries.compute(key, (k, current) -> --entry.references == 0 ? null : entry);
  }

  private static ApplicationException busy() {
    return new ApplicationException(ApplicationException.Kind.BUSY, "该会话或请求正在处理中，请稍后重试");
  }

  interface Lease extends AutoCloseable {
    @Override
    void close();
  }
}
