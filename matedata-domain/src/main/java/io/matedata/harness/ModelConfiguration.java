package io.matedata.harness;

/**
 * Model service used by the query agent. Configurations saved before providers existed have no
 * provider and keep their original OpenAI-compatible meaning.
 */
public record ModelConfiguration(
    String baseUrl,
    String model,
    String encryptedKey,
    int maxSteps,
    int timeoutSeconds,
    Provider provider) {
  public enum Provider {
    /** Any Chat Completions service with tool calling: OpenAI, DashScope, DeepSeek and others. */
    OPENAI_COMPATIBLE,
    /** A local or self-hosted Ollama server; no API key. */
    OLLAMA
  }

  public ModelConfiguration {
    provider = provider == null ? Provider.OPENAI_COMPATIBLE : provider;
    encryptedKey = encryptedKey == null ? "" : encryptedKey;
  }

  public boolean requiresKey() {
    return provider == Provider.OPENAI_COMPATIBLE;
  }
}
