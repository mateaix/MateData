package io.matedata.harness.application;

import io.matedata.shared.infrastructure.DocumentStore;
import io.matedata.shared.infrastructure.SecretVault;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.util.Map;

@Service
public class ModelSettings {
    public record Configuration(String baseUrl,String model,String encryptedKey,int maxSteps,int timeoutSeconds){}
    private final DocumentStore store;private final SecretVault vault;
    public ModelSettings(DocumentStore store,SecretVault vault){this.store=store;this.vault=vault;}
    public Configuration current(){return store.get("harness-settings","model",Configuration.class).orElse(new Configuration("https://api.openai.com/v1","","",6,60));}
    public boolean configured(){var c=current();return !c.model().isBlank()&&!c.encryptedKey().isBlank();}
    public String key(Configuration c){return vault.decrypt(c.encryptedKey());}
    public Map<String,Object> view(){var c=current();return Map.of("baseUrl",c.baseUrl(),"model",c.model(),"configured",configured(),"maxSteps",c.maxSteps(),"timeoutSeconds",c.timeoutSeconds());}
    public synchronized Map<String,Object> save(String baseUrl,String model,String apiKey,int maxSteps,int timeoutSeconds) {
        URI uri;
        try{uri=URI.create(baseUrl);}catch(Exception e){throw new IllegalArgumentException("模型地址不合法");}
        if(!java.util.Set.of("https","http").contains(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)throw new IllegalArgumentException("模型地址应为 HTTP(S) 服务地址，不应包含凭证或查询参数");
        if(model==null||model.isBlank()||model.length()>120||maxSteps<1||maxSteps>12||timeoutSeconds<5||timeoutSeconds>120)throw new IllegalArgumentException("请填写模型名称，步数 1–12，超时 5–120 秒");
        var old=current();String secret=apiKey==null||apiKey.isBlank()?old.encryptedKey():vault.encrypt(apiKey);
        if(secret.isBlank())throw new IllegalArgumentException("首次配置需要 API Key");
        store.save("harness-settings","model",new Configuration(baseUrl.replaceAll("/+$",""),model,secret,maxSteps,timeoutSeconds));return view();
    }
}
