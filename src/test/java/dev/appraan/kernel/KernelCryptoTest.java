package dev.appraan.kernel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Manual test for {@link KernelCrypto} (the AES-256-GCM feed envelope,
 * layout {@code [0x01][flags][12B nonce][ct+tag]}, header bytes bound by
 * GCM as AAD). Includes known-answer vectors — fixed key, fixed nonce,
 * plaintext {@code "appraan"} — for BOTH payload variants (plain JSON
 * inside, flags=0; gzipped inside, flags=1), generated with this
 * implementation; any conforming AES-256-GCM implementation (e.g.
 * .NET's {@code AesGcm} in appraanagent) must produce/decrypt the
 * identical bytes.
 * Run via {@code ./run-tests.sh}; exits non-zero (AssertionError) on failure.
 */
public final class KernelCryptoTest {

    private static int checks;

    // KAT: key = bytes 0..31, nonce = bytes 0..11, plaintext "appraan".
    private static final String KAT_KEY_B64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    private static final String KAT_PLAIN_ENVELOPE_B64 =
            "AQAAAQIDBAUGBwgJCgsmcqZppISsNYouDg77DGTvcIXhIxK2Mw==";
    private static final String KAT_GZIP_ENVELOPE_B64 =
            "AQEAAQIDBAUGBwgJCgsmcqZppISshs2yPWhC33oW5d8QB3iUtA==";

    public static void main(String[] args) throws Exception {
        knownAnswerVectors();
        roundTripBothVariants();
        tamperAndWrongKeyRejected();
        keyFileLoading();
        System.out.println("KernelCryptoTest: all " + checks + " checks passed");
    }

    private static void knownAnswerVectors() {
        byte[] key = Base64.getDecoder().decode(KAT_KEY_B64);
        byte[] nonce = new byte[12];
        for (int i = 0; i < nonce.length; i++) {
            nonce[i] = (byte) i;
        }
        byte[] plainEnv = KernelCrypto.encrypt(key, nonce,
                "appraan".getBytes(StandardCharsets.UTF_8), false);
        check(KAT_PLAIN_ENVELOPE_B64.equals(Base64.getEncoder().encodeToString(plainEnv)),
                "KAT plain-inside: envelope bytes match the known answer");
        check(plainEnv[0] == 0x01 && plainEnv[1] == 0 && plainEnv.length == 2 + 12 + 7 + 16,
                "KAT plain-inside: layout [0x01][flags=0][12B nonce][ct+16B tag]");

        byte[] gzipEnv = KernelCrypto.encrypt(key, nonce,
                "appraan".getBytes(StandardCharsets.UTF_8), true);
        check(KAT_GZIP_ENVELOPE_B64.equals(Base64.getEncoder().encodeToString(gzipEnv)),
                "KAT gzip-flagged: envelope bytes match the known answer");
        check(gzipEnv[1] == KernelCrypto.FLAG_GZIPPED, "KAT gzip-flagged: flags bit0 set");

        KernelCrypto.Decrypted d1 = KernelCrypto.decrypt(key,
                Base64.getDecoder().decode(KAT_PLAIN_ENVELOPE_B64));
        check("appraan".equals(new String(d1.payload(), StandardCharsets.UTF_8)) && !d1.gzipped(),
                "KAT plain-inside decrypts, gzipped=false");
        KernelCrypto.Decrypted d2 = KernelCrypto.decrypt(key,
                Base64.getDecoder().decode(KAT_GZIP_ENVELOPE_B64));
        check("appraan".equals(new String(d2.payload(), StandardCharsets.UTF_8)) && d2.gzipped(),
                "KAT gzip-flagged decrypts, gzipped=true");
    }

    private static void roundTripBothVariants() {
        byte[] key = KernelCrypto.generateKey();
        byte[] data = "{\"pid\":1}".getBytes(StandardCharsets.UTF_8);
        for (boolean gz : new boolean[]{false, true}) {
            byte[] a = KernelCrypto.encrypt(key, data, gz);
            byte[] b = KernelCrypto.encrypt(key, data, gz);
            check(!java.util.Arrays.equals(a, b), "random nonce -> distinct envelopes (gz=" + gz + ")");
            KernelCrypto.Decrypted d = KernelCrypto.decrypt(key, a);
            check(java.util.Arrays.equals(d.payload(), data) && d.gzipped() == gz,
                    "round trip (gz=" + gz + ")");
        }
    }

    private static void tamperAndWrongKeyRejected() {
        byte[] key = KernelCrypto.generateKey();
        byte[] envelope = KernelCrypto.encrypt(key,
                "payload".getBytes(StandardCharsets.UTF_8), false);
        byte[] tamperedTag = envelope.clone();
        tamperedTag[tamperedTag.length - 1] ^= 0x01;
        expectSecurity(() -> KernelCrypto.decrypt(key, tamperedTag), "flipped tag byte rejected");
        byte[] tamperedFlags = envelope.clone();
        tamperedFlags[1] ^= 0x01;
        expectSecurity(() -> KernelCrypto.decrypt(key, tamperedFlags),
                "flipped flags byte rejected (header is GCM AAD)");
        byte[] unknownFlags = envelope.clone();
        unknownFlags[1] = 0x04;
        expectSecurity(() -> KernelCrypto.decrypt(key, unknownFlags), "unknown flag bits rejected");
        byte[] wrongKey = KernelCrypto.generateKey();
        expectSecurity(() -> KernelCrypto.decrypt(wrongKey, envelope), "wrong key rejected");
        byte[] badVersion = envelope.clone();
        badVersion[0] = 0x02;
        expectSecurity(() -> KernelCrypto.decrypt(key, badVersion), "unknown version rejected");
        expectSecurity(() -> KernelCrypto.decrypt(key, new byte[]{0x01, 0, 1, 2}),
                "truncated envelope rejected");
    }

    private static void keyFileLoading() throws Exception {
        Path dir = Files.createTempDirectory("appraan-key-test");
        Path good = dir.resolve("key.b64");
        Files.writeString(good, KAT_KEY_B64 + "\n");
        check(KernelCrypto.loadKey(good).length == 32, "key file loads 32 bytes");

        Path shortKey = dir.resolve("short.b64");
        Files.writeString(shortKey, Base64.getEncoder().encodeToString(new byte[16]));
        checks++;
        try {
            KernelCrypto.loadKey(shortKey);
            throw new AssertionError("KernelCryptoTest FAILED: 16-byte key accepted");
        } catch (java.io.IOException expected) {
            // AES-128 keys are not accepted: the contract is AES-256 only.
        }
        Path garbage = dir.resolve("garbage.b64");
        Files.writeString(garbage, "not base64!!");
        checks++;
        try {
            KernelCrypto.loadKey(garbage);
            throw new AssertionError("KernelCryptoTest FAILED: garbage key file accepted");
        } catch (java.io.IOException expected) {
            // rejected as required
        }
    }

    private static void expectSecurity(Runnable r, String label) {
        checks++;
        try {
            r.run();
        } catch (SecurityException expected) {
            return;
        }
        throw new AssertionError("KernelCryptoTest FAILED: " + label);
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) {
            throw new AssertionError("KernelCryptoTest FAILED: " + label);
        }
    }
}
