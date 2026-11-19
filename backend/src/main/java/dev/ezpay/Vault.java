package dev.ezpay;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

@Component
class Vault {
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec encryptionKey;
    private final SecretKeySpec indexKey;
    private final ObjectMapper json;

    Vault(ObjectMapper json, @Value("${ezpay.key:}") String configured) throws Exception {
        this.json = json;
        byte[] material;
        if (!configured.isBlank()) {
            material = Base64.getDecoder().decode(configured);
        } else {
            Files.createDirectories(Path.of("data"));
            Path file = Path.of("data", "vault.key");
            if (Files.notExists(file)) {
                try {
                    Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                } catch (UnsupportedOperationException exception) {
                    Files.createFile(file);
                }
                Files.write(file, random.generateSeed(64));
            }
            material = Files.readAllBytes(file);
        }
        if (material.length != 64) throw new IllegalArgumentException("EZPAY_KEY must decode to 64 bytes");
        encryptionKey = new SecretKeySpec(Arrays.copyOfRange(material, 0, 32), "AES");
        indexKey = new SecretKeySpec(Arrays.copyOfRange(material, 32, 64), "HmacSHA256");
    }

    String index(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(indexKey);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Lookup key generation failed", exception);
        }
    }

    boolean matches(String expected, String supplied) {
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), index(supplied).getBytes(StandardCharsets.UTF_8));
    }

    String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    String otp() {
        return String.format(java.util.Locale.ROOT, "%06d", random.nextInt(1_000_000));
    }

    String seal(String context, Map<String, String> value) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce, context);
            byte[] encrypted = cipher.doFinal(json.writeValueAsBytes(value));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array());
        } catch (Exception exception) {
            throw new IllegalStateException("Encryption failed", exception);
        }
    }

    Map<String, String> open(String context, String value) {
        try {
            byte[] encoded = Base64.getDecoder().decode(value);
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, Arrays.copyOf(encoded, 12), context);
            return json.readValue(cipher.doFinal(Arrays.copyOfRange(encoded, 12, encoded.length)), new TypeReference<>() {});
        } catch (Exception exception) {
            throw new IllegalStateException("Decryption failed", exception);
        }
    }

    private Cipher cipher(int mode, byte[] nonce, String context) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, encryptionKey, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
