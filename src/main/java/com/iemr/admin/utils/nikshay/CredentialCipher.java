package com.iemr.admin.utils.nikshay;


import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.iemr.admin.config.NikshayProperties;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM for m_nikshay_device_configuration.password.
 * Stored value = Base64( 12-byte IV || ciphertext+tag ). The key comes from
 * nikshay.credential.key-base64 (env NIKSHAY_CREDENTIAL_KEY_BASE64), never from the DB.
 * encrypt() is here so the admin/seed step can produce the stored value.
 */
@Component
public class CredentialCipher {

    private static final String ALGO = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final NikshayProperties props;
    private final SecureRandom random = new SecureRandom();
    private volatile SecretKey key;

    public CredentialCipher(NikshayProperties props) {
        this.props = props;
    }

    /** Resolved on first use so a missing key fails the Ni-kshay call, not application start-up. */
    private SecretKey key() {
        SecretKey k = key;
        if (k == null) {
            String b64 = props.getCredential().getKeyBase64();
            if (b64 == null || b64.isBlank() || b64.startsWith("${")) {
                throw new IllegalStateException("nikshay.credential.key-base64 is not set");
            }
            byte[] raw = Base64.getDecoder().decode(b64.trim());
            if (raw.length != 32) {
                throw new IllegalStateException("nikshay.credential.key-base64 must decode to 32 bytes, got " + raw.length);
            }
            k = new SecretKeySpec(raw, "AES");
            key = k;
        }
        return k;
    }

    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance(ALGO);
            c.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_BYTES + ct.length];
            System.arraycopy(iv, 0, out, 0, IV_BYTES);
            System.arraycopy(ct, 0, out, IV_BYTES, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Password encryption failed", e);
        }
    }

    public String decrypt(String stored) {
        try {
            byte[] all = Base64.getDecoder().decode(stored.trim());
            if (all.length <= IV_BYTES) {
                throw new IllegalStateException("Encrypted value too short");
            }
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(all, 0, iv, 0, IV_BYTES);
            Cipher c = Cipher.getInstance(ALGO);
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = c.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Password decryption failed – wrong key or corrupt value", e);
        }
    }
}
