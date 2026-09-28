package io.matedata.conversation.application;

import static org.assertj.core.api.Assertions.*;

import io.matedata.shared.ApplicationException;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class QueryCoordinatorTest {
  @Test
  void timedOutWaiterDoesNotBreakHolderOrLaterAcquisition() throws Exception {
    var coordinator = new QueryCoordinator();
    try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
      try (var held = coordinator.acquire("same")) {
        pool.submit(
                () ->
                    assertThatThrownBy(() -> coordinator.acquire("same"))
                        .isInstanceOfSatisfying(
                            ApplicationException.class,
                            e -> assertThat(e.kind()).isEqualTo(ApplicationException.Kind.BUSY)))
            .get(3, TimeUnit.SECONDS);
        pool.submit(
                () -> {
                  try (var other = coordinator.acquire("independent")) {
                    return true;
                  }
                })
            .get(1, TimeUnit.SECONDS);
      }
      assertThat(
              pool.submit(
                      () -> {
                        try (var later = coordinator.acquire("same")) {
                          return true;
                        }
                      })
                  .get(1, TimeUnit.SECONDS))
          .isTrue();
    }
  }
}
