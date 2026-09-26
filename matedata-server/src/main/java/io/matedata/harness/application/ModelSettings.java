package io.matedata.harness.application;

import io.matedata.harness.ModelConfiguration;
import io.matedata.harness.ModelConfiguration.Provider;
import io.matedata.harness.ModelConfigurationRepository;
import io.matedata.shared.CredentialCipher;
import java.net.URI;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ModelSettings {
  private final ModelConfigurationRepository repository;
  private final CredentialCipher vault;

  public ModelSettings(ModelConfigurationRepository repository, CredentialCipher vault) {
    this.repository = repository;
    this.vault = vault;
  }

  public ModelConfiguration current() {
    return repository
        .current()
        .orElse(
            new ModelConfiguration(
                "https://api.openai.com/v1", "", "", 6, 60, Provider.OPENAI_COMPATIBLE));
  }

  public boolean configured() {
    var c = current();
    return !c.model().isBlank() && (!c.requiresKey() || !c.encryptedKey().isBlank());
  }

  public String key(ModelConfiguration c) {
    return c.encryptedKey().isBlank() ? "" : vault.decrypt(c.encryptedKey());
  }

  public Map<String, Object> view() {
    var c = current();
    return Map.of(
        "provider",
        c.provider().name(),
        "baseUrl",
        c.baseUrl(),
        "model",
        c.model(),
        "configured",
        configured(),
        "maxSteps",
        c.maxSteps(),
        "timeoutSeconds",
        c.timeoutSeconds());
  }

  /** Saves an OpenAI-compatible configuration; kept for callers that predate providers. */
  public Map<String, Object> save(
      String baseUrl, String model, String apiKey, int maxSteps, int timeoutSeconds) {
    return save("OPENAI_COMPATIBLE", baseUrl, model, apiKey, maxSteps, timeoutSeconds);
  }

  public synchronized Map<String, Object> save(
      String provider,
      String baseUrl,
      String model,
      String apiKey,
      int maxSteps,
      int timeoutSeconds) {
    Provider selected;
    try {
      selected = Provider.valueOf(provider == null ? "" : provider);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("请选择模型服务类型：OpenAI 兼容或 Ollama");
    }
    URI uri;
    try {
      uri = URI.create(baseUrl);
    } catch (Exception e) {
      throw new IllegalArgumentException("模型地址不合法");
    }
    if (uri.getScheme() == null
        || !java.util.Set.of("https", "http").contains(uri.getScheme())
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null)
      throw new IllegalArgumentException("模型地址应为 HTTP(S) 服务地址，不应包含凭证或查询参数");
    if (model == null
        || model.isBlank()
        || model.length() > 120
        || maxSteps < 1
        || maxSteps > 12
        || timeoutSeconds < 5
        || timeoutSeconds > 300) throw new IllegalArgumentException("请填写模型名称，步数 1–12，超时 5–300 秒");
    var old = current();
    String secret;
    if (selected == Provider.OLLAMA) {
      // Ollama has no key; never keep an unused secret from a previous provider.
      secret = "";
    } else {
      boolean reuse = apiKey == null || apiKey.isBlank();
      secret =
          reuse
              ? (old.provider() == Provider.OPENAI_COMPATIBLE ? old.encryptedKey() : "")
              : vault.encrypt(apiKey);
      if (secret.isBlank()) throw new IllegalArgumentException("OpenAI 兼容服务需要 API Key");
    }
    repository.save(
        new ModelConfiguration(
            baseUrl.replaceAll("/+$", ""), model, secret, maxSteps, timeoutSeconds, selected));
    return view();
  }
}
