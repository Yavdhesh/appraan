package dev.appraan.kernel;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * The shared-key feed envelope: AES-256-GCM over the snapshot JSON,
 * shared verbatim with the appraanagent producer (.NET {@code AesGcm}).
 * Wire layout:
 *
 * <pre>[0x01 version][flags][12-byte random nonce][ciphertext + 16-byte GCM tag]</pre>
 *
 * The flags byte records how the plaintext inside was produced:
 * bit 0 set = the JSON was gzip-compressed before sealing (the agent's
 * opt-in {@code --gzip}); clear = literal plain JSON inside. The two
 * header bytes are fed to GCM as additional authenticated data, so
 * header tampering fails decryption like any other forgery. Encryption
 * itself is also opt-in — with no key configured anywhere, the feed is
 * plain JSON over HTTP.
 *
 * Key files hold base64 of the 32 key bytes on one line (create one with
 * {@code appraanagent --gen-key}). JDK built-ins only — zero dependencies,
 * like the rest of the project.
 */
public final class KernelCrypto {

    public static final int KEY_BYTES = 32;
    public static final int NONCE_BYTES = 12;
    public static final int TAG_BITS = 128;
    public static final byte VERSION = 0x01;
    /** Flags bit: the sealed payload is gzip-compressed JSON. */
    public static final int FLAG_GZIPPED = 0x01;

    private static final SecureRandom RANDOM = new SecureRandom();

    private KernelCrypto() {
    }

    /** A decrypted envelope: the payload bytes + whether they are gzipped. */
    public record Decrypted(byte[] payload, boolean gzipped) {
    }

    /** Loads a key file (base64 of 32 bytes, one line; whitespace tolerated). */
    public static byte[] loadKey(Path file) throws IOException {
        String text = Files.readString(file).trim();
        byte[] key;
        try {
            key = Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            throw new IOException("kernel key file is not valid base64: " + file, e);
        }
        if (key.length != KEY_BYTES) {
            throw new IOException("kernel key must be " + KEY_BYTES + " bytes (AES-256), got "
                    + key.length + ": " + file);
        }
        return key;
    }

    /** A fresh random 256-bit key. */
    public static byte[] generateKey() {
        byte[] key = new byte[KEY_BYTES];
        RANDOM.nextBytes(key);
        return key;
    }

    /** Encrypts with a fresh random nonce. */
    public static byte[] encrypt(byte[] key, byte[] plaintext, boolean gzipped) {
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        return encrypt(key, nonce, plaintext, gzipped);
    }

    /** Encrypts with an explicit nonce (package-private: known-answer tests). */
    static byte[] encrypt(byte[] key, byte[] nonce, byte[] plaintext, boolean gzipped) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            byte flags = (byte) (gzipped ? FLAG_GZIPPED : 0);
            cipher.updateAAD(new byte[]{VERSION, flags});
            byte[] ct = cipher.doFinal(plaintext); // ciphertext + tag appended
            byte[] envelope = new byte[2 + nonce.length + ct.length];
            envelope[0] = VERSION;
            envelope[1] = flags;
            System.arraycopy(nonce, 0, envelope, 2, nonce.length);
            System.arraycopy(ct, 0, envelope, 2 + nonce.length, ct.length);
            return envelope;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encryption failed", e);
        }
    }

    /**
     * Decrypts an envelope. Any defect — bad version, unknown flag bits,
     * truncation, wrong key, tampering — is a {@link SecurityException};
     * nothing is returned partially.
     */
    public static Decrypted decrypt(byte[] key, byte[] envelope) {
        if (envelope == null || envelope.length < 2 + NONCE_BYTES + TAG_BITS / 8 + 1) {
            throw new SecurityException("envelope too short");
        }
        if (envelope[0] != VERSION) {
            throw new SecurityException("unsupported envelope version " + envelope[0]);
        }
        int flags = envelope[1] & 0xFF;
        if ((flags & ~FLAG_GZIPPED) != 0) {
            throw new SecurityException("unknown envelope flags " + flags);
        }
        byte[] nonce = new byte[NONCE_BYTES];
        System.arraycopy(envelope, 2, nonce, 0, NONCE_BYTES);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(new byte[]{envelope[0], envelope[1]});
            byte[] payload = cipher.doFinal(envelope, 2 + NONCE_BYTES,
                    envelope.length - 2 - NONCE_BYTES);
            return new Decrypted(payload, (flags & FLAG_GZIPPED) != 0);
        } catch (GeneralSecurityException e) {
            throw new SecurityException("envelope decryption failed", e);
        }
    }
}
