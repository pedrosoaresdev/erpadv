package br.com.erpadv.firebase;

import br.com.erpadv.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Criptografia de dados pessoais sensíveis (LGPD): AES-256-GCM para armazenamento e HMAC-SHA256
 * para permitir busca exata (ex.: por CPF) sem guardar o valor em claro.
 */
@Service
public class CryptoService {

    private static final Logger log = LoggerFactory.getLogger(CryptoService.class);
    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    /** Chaves publicadas no .env.example — aceitas apenas fora de produção. */
    static final String DEV_ENCRYPTION_KEY = "ZGV2LW9ubHkta2V5LWNoYW5nZS1tZS0zMi1ieXRlcyE=";
    static final String DEV_HMAC_KEY = "ZGV2LW9ubHktaG1hYy1rZXktY2hhbmdlLW1lLTMyYnl0ZXM=";

    private final SecretKeySpec encryptionKey;
    private final SecretKeySpec hmacKey;
    private final SecureRandom random = new SecureRandom();

    public CryptoService(AppProperties properties) {
        AppProperties.Security sec = properties.security();
        byte[] enc = decodeKey("DATA_ENCRYPTION_KEY", sec.dataEncryptionKey());
        byte[] mac = decodeKey("SEARCH_HMAC_KEY", sec.searchHmacKey());
        if (enc.length != 32) {
            throw new IllegalStateException("DATA_ENCRYPTION_KEY deve ter exatamente 32 bytes (base64).");
        }
        if (mac.length < 32) {
            throw new IllegalStateException("SEARCH_HMAC_KEY deve ter ao menos 32 bytes (base64).");
        }
        boolean devKeys = DEV_ENCRYPTION_KEY.equals(sec.dataEncryptionKey()) || DEV_HMAC_KEY.equals(sec.searchHmacKey());
        if (devKeys && sec.production()) {
            throw new IllegalStateException("Chaves de desenvolvimento não podem ser usadas em produção (APP_PRODUCTION=true).");
        }
        if (devKeys) {
            log.warn("Usando chaves de criptografia de DESENVOLVIMENTO. Gere chaves próprias antes de usar dados reais.");
        }
        this.encryptionKey = new SecretKeySpec(enc, "AES");
        this.hmacKey = new SecretKeySpec(mac, "HmacSHA256");
    }

    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            ByteBuffer buffer = ByteBuffer.allocate(iv.length + cipherText.length).put(iv).put(cipherText);
            return PREFIX + Base64.getEncoder().encodeToString(buffer.array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao criptografar dado sensível.", e);
        }
    }

    public String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isEmpty()) {
            return null;
        }
        if (!encrypted.startsWith(PREFIX)) {
            throw new IllegalStateException("Formato de dado criptografado desconhecido.");
        }
        try {
            byte[] all = Base64.getDecoder().decode(encrypted.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao descriptografar dado sensível.", e);
        }
    }

    private static final byte[] BYTES_MAGIC = {'E', 'R', 'P', '1'};

    /** Cifra um arquivo inteiro (AES-256-GCM): "ERP1" + IV + texto cifrado com tag. */
    public byte[] encryptBytes(byte[] plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(BYTES_MAGIC);
            byte[] cipherText = cipher.doFinal(plain);
            return ByteBuffer.allocate(BYTES_MAGIC.length + iv.length + cipherText.length).put(BYTES_MAGIC).put(iv).put(cipherText).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao criptografar arquivo.", e);
        }
    }

    public byte[] decryptBytes(byte[] data) {
        if (data == null || data.length < BYTES_MAGIC.length + IV_BYTES
                || !java.util.Arrays.equals(java.util.Arrays.copyOf(data, BYTES_MAGIC.length), BYTES_MAGIC)) {
            throw new IllegalStateException("Arquivo cifrado em formato desconhecido.");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, new GCMParameterSpec(TAG_BITS, data, BYTES_MAGIC.length, IV_BYTES));
            cipher.updateAAD(BYTES_MAGIC);
            int offset = BYTES_MAGIC.length + IV_BYTES;
            return cipher.doFinal(data, offset, data.length - offset);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao descriptografar arquivo (chave diferente ou arquivo alterado).", e);
        }
    }

    /** HMAC determinístico (base64url, sem padding) — usado como token de busca exata. */
    public String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(hmacKey);
            byte[] out = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Falha ao calcular HMAC.", e);
        }
    }

    private static byte[] decodeKey(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " não configurada. Veja o .env.example.");
        }
        try {
            return Base64.getDecoder().decode(value.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(name + " deve estar em base64.", e);
        }
    }
}
