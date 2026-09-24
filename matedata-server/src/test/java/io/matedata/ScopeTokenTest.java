package io.matedata;

import static org.assertj.core.api.Assertions.*;

import io.matedata.shared.infrastructure.SecretVault;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScopeTokenTest {
  @TempDir Path directory;

  @Test
  void publicTokensAreKeyedStableAcrossRestartAndSeparateFromRawFingerprints() throws Exception {
    var first = new SecretVault(directory.resolve("one").toString(), "");
    var restarted = new SecretVault(directory.resolve("one").toString(), "");
    var other = new SecretVault(directory.resolve("two").toString(), "");
    String raw = "a".repeat(64);
    String token = first.encodeFingerprint(raw);
    assertThat(token).matches("[a-f0-9]{64}").isNotEqualTo(raw);
    assertThat(restarted.encodeFingerprint(raw)).isEqualTo(token);
    assertThat(other.encodeFingerprint(raw)).isNotEqualTo(token);
    assertThat(first.encodeFingerprint("b".repeat(64))).isNotEqualTo(token);
  }
}
