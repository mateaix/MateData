package io.matedata.shared.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

@Component
public class SecretVault {
    private final byte[] key;
    public SecretVault(@Value("${matedata.data-dir}") String directory,@Value("${matedata.encryption-key:}") String supplied) throws Exception {
        if (!supplied.isBlank()) key=Base64.getDecoder().decode(supplied);
        else {
            var path=Path.of(directory).toAbsolutePath().resolve("secret.key"); Files.createDirectories(path.getParent());
            if (!Files.exists(path)) {
                byte[] fresh=new byte[32]; new SecureRandom().nextBytes(fresh);
                Files.writeString(path,Base64.getEncoder().encodeToString(fresh),StandardOpenOption.CREATE_NEW);
                try {Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}
            }
            key=Base64.getDecoder().decode(Files.readString(path).trim());
        }
        if(key.length!=32) throw new IllegalArgumentException("加密密钥必须为 Base64 编码的 32 字节");
    }
    public String encrypt(String value) {
        try {
            byte[] iv=new byte[12]; new SecureRandom().nextBytes(iv); var c=Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,iv));
            return Base64.getEncoder().encodeToString(iv)+":"+Base64.getEncoder().encodeToString(c.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        }catch(Exception e){throw new IllegalStateException("凭证加密失败",e);}
    }
    public String decrypt(String value) {
        try {
            var parts=value.split(":",2);var c=Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Base64.getDecoder().decode(parts[0])));
            return new String(c.doFinal(Base64.getDecoder().decode(parts[1])),StandardCharsets.UTF_8);
        }catch(Exception e){throw new IllegalStateException("凭证解密失败，请检查加密密钥",e);}
    }
}
