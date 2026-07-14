package com.ghostsign.app;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

public final class GhostWatermark {
    private static final String KEY_ALIAS = "ghostsign_identity_v1";
    private static final byte VERSION = 1;
    private static final byte[] DOMAIN = "GhostSign/v1".getBytes(StandardCharsets.UTF_8);

    // Marker characters are invisible Unicode separators. Payload characters carry two bits each.
    private static final String START = "\u2063\u2064\u2063\u2064";
    private static final String END = "\u2064\u2063\u2064\u2063";
    private static final char[] ALPHABET = {'\u200B', '\u200C', '\u200D', '\u2060'};
    private static final int PUBLIC_KEY_BYTES = 65;
    private static final int SIGNATURE_BYTES = 64;
    private static final int PAYLOAD_BYTES = 1 + 4 + 4 + PUBLIC_KEY_BYTES + SIGNATURE_BYTES;

    private GhostWatermark() {}

    public static String sign(String text) throws Exception {
        String visible = stripWatermarks(text == null ? "" : text);
        KeyPair pair = getOrCreateKeyPair();
        byte[] publicKey = publicKeyToRaw((ECPublicKey) pair.getPublic());
        int timestamp = (int) (System.currentTimeMillis() / 1000L);
        int nonce = new SecureRandom().nextInt();
        byte[] digest = sha256(normalize(visible).getBytes(StandardCharsets.UTF_8));
        byte[] signedData = signedData(timestamp, nonce, publicKey, digest);

        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(pair.getPrivate());
        signer.update(signedData);
        byte[] rawSignature = derToRaw(signer.sign());

        ByteBuffer payload = ByteBuffer.allocate(PAYLOAD_BYTES);
        payload.put(VERSION);
        payload.putInt(timestamp);
        payload.putInt(nonce);
        payload.put(publicKey);
        payload.put(rawSignature);
        return visible + START + encode(payload.array()) + END;
    }

    public static Verification verify(String text) {
        try {
            if (text == null) {
                return Verification.invalid("No text was supplied.");
            }
            int start = text.lastIndexOf(START);
            if (start < 0) {
                return Verification.invalid("No GhostSign watermark found.");
            }
            int end = text.indexOf(END, start + START.length());
            if (end < 0) {
                return Verification.invalid("The watermark is incomplete or was stripped.");
            }

            byte[] payload = decode(text.substring(start + START.length(), end));
            if (payload.length != PAYLOAD_BYTES) {
                return Verification.invalid("The watermark payload is damaged.");
            }

            ByteBuffer buffer = ByteBuffer.wrap(payload);
            byte version = buffer.get();
            if (version != VERSION) {
                return Verification.invalid("Unsupported GhostSign version.");
            }
            int timestamp = buffer.getInt();
            int nonce = buffer.getInt();
            byte[] publicRaw = new byte[PUBLIC_KEY_BYTES];
            byte[] signatureRaw = new byte[SIGNATURE_BYTES];
            buffer.get(publicRaw);
            buffer.get(signatureRaw);

            String visible = stripWatermarks(text);
            byte[] digest = sha256(normalize(visible).getBytes(StandardCharsets.UTF_8));
            PublicKey publicKey = rawToPublicKey(publicRaw);
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey);
            verifier.update(signedData(timestamp, nonce, publicRaw, digest));
            boolean valid = verifier.verify(rawToDer(signatureRaw));
            String fingerprint = fingerprint(publicRaw);
            if (!valid) {
                return new Verification(false, "Signature mismatch: the visible text was changed or the watermark is damaged.", fingerprint, timestamp, visible);
            }
            return new Verification(true, "Authentic GhostSign watermark.", fingerprint, timestamp, visible);
        } catch (Exception error) {
            return Verification.invalid("Could not decode watermark: " + error.getMessage());
        }
    }

    public static String stripWatermarks(String text) {
        if (text == null || text.isEmpty()) {
            return text == null ? "" : text;
        }
        String result = text;
        while (true) {
            int start = result.lastIndexOf(START);
            if (start < 0) {
                return result;
            }
            int end = result.indexOf(END, start + START.length());
            if (end < 0) {
                return result;
            }
            result = result.substring(0, start) + result.substring(end + END.length());
        }
    }

    public static boolean hasWatermark(String text) {
        return text != null && text.contains(START) && text.contains(END);
    }

    public static String currentFingerprint() throws Exception {
        return fingerprint(publicKeyToRaw((ECPublicKey) getOrCreateKeyPair().getPublic()));
    }

    private static KeyPair getOrCreateKeyPair() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) {
            PrivateKey privateKey = (PrivateKey) store.getKey(KEY_ALIAS, null);
            PublicKey publicKey = store.getCertificate(KEY_ALIAS).getPublicKey();
            return new KeyPair(publicKey, privateKey);
        }

        KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build();
        generator.initialize(spec);
        return generator.generateKeyPair();
    }

    private static byte[] signedData(int timestamp, int nonce, byte[] publicKey, byte[] textDigest) {
        ByteBuffer buffer = ByteBuffer.allocate(DOMAIN.length + 1 + 4 + 4 + publicKey.length + textDigest.length);
        buffer.put(DOMAIN);
        buffer.put(VERSION);
        buffer.putInt(timestamp);
        buffer.putInt(nonce);
        buffer.put(publicKey);
        buffer.put(textDigest);
        return buffer.array();
    }

    private static byte[] publicKeyToRaw(ECPublicKey publicKey) {
        byte[] x = fixed32(publicKey.getW().getAffineX());
        byte[] y = fixed32(publicKey.getW().getAffineY());
        byte[] raw = new byte[65];
        raw[0] = 0x04;
        System.arraycopy(x, 0, raw, 1, 32);
        System.arraycopy(y, 0, raw, 33, 32);
        return raw;
    }

    private static PublicKey rawToPublicKey(byte[] raw) throws Exception {
        if (raw.length != 65 || raw[0] != 0x04) {
            throw new IllegalArgumentException("Invalid public key");
        }
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(raw, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(raw, 33, 65));
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec ec = parameters.getParameterSpec(ECParameterSpec.class);
        ECPublicKeySpec spec = new ECPublicKeySpec(new ECPoint(x, y), ec);
        return KeyFactory.getInstance("EC").generatePublic(spec);
    }

    private static byte[] fixed32(BigInteger value) {
        byte[] source = value.toByteArray();
        byte[] result = new byte[32];
        int sourceOffset = Math.max(0, source.length - 32);
        int length = Math.min(32, source.length);
        System.arraycopy(source, sourceOffset, result, 32 - length, length);
        return result;
    }

    private static String encode(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 4);
        for (byte value : bytes) {
            int unsigned = value & 0xFF;
            output.append(ALPHABET[(unsigned >>> 6) & 3]);
            output.append(ALPHABET[(unsigned >>> 4) & 3]);
            output.append(ALPHABET[(unsigned >>> 2) & 3]);
            output.append(ALPHABET[unsigned & 3]);
        }
        return output.toString();
    }

    private static byte[] decode(String encoded) {
        if (encoded.length() % 4 != 0) {
            throw new IllegalArgumentException("Invalid invisible character count");
        }
        byte[] bytes = new byte[encoded.length() / 4];
        for (int i = 0; i < bytes.length; i++) {
            int value = 0;
            for (int j = 0; j < 4; j++) {
                int digit = alphabetIndex(encoded.charAt(i * 4 + j));
                if (digit < 0) {
                    throw new IllegalArgumentException("Unknown invisible character");
                }
                value = (value << 2) | digit;
            }
            bytes[i] = (byte) value;
        }
        return bytes;
    }

    private static int alphabetIndex(char value) {
        for (int i = 0; i < ALPHABET.length; i++) {
            if (ALPHABET[i] == value) {
                return i;
            }
        }
        return -1;
    }

    private static byte[] derToRaw(byte[] der) {
        int[] cursor = {0};
        expect(der, cursor, 0x30);
        readLength(der, cursor);
        expect(der, cursor, 0x02);
        int rLength = readLength(der, cursor);
        byte[] r = Arrays.copyOfRange(der, cursor[0], cursor[0] + rLength);
        cursor[0] += rLength;
        expect(der, cursor, 0x02);
        int sLength = readLength(der, cursor);
        byte[] s = Arrays.copyOfRange(der, cursor[0], cursor[0] + sLength);
        byte[] raw = new byte[64];
        copyInteger(r, raw, 0);
        copyInteger(s, raw, 32);
        return raw;
    }

    private static byte[] rawToDer(byte[] raw) {
        if (raw.length != 64) {
            throw new IllegalArgumentException("Invalid raw signature");
        }
        byte[] r = unsignedInteger(Arrays.copyOfRange(raw, 0, 32));
        byte[] s = unsignedInteger(Arrays.copyOfRange(raw, 32, 64));
        int total = 2 + r.length + 2 + s.length;
        ByteBuffer buffer = ByteBuffer.allocate(2 + total);
        buffer.put((byte) 0x30);
        buffer.put((byte) total);
        buffer.put((byte) 0x02);
        buffer.put((byte) r.length);
        buffer.put(r);
        buffer.put((byte) 0x02);
        buffer.put((byte) s.length);
        buffer.put(s);
        return buffer.array();
    }

    private static byte[] unsignedInteger(byte[] fixed) {
        int first = 0;
        while (first < fixed.length - 1 && fixed[first] == 0) {
            first++;
        }
        byte[] stripped = Arrays.copyOfRange(fixed, first, fixed.length);
        if ((stripped[0] & 0x80) != 0) {
            byte[] padded = new byte[stripped.length + 1];
            System.arraycopy(stripped, 0, padded, 1, stripped.length);
            return padded;
        }
        return stripped;
    }

    private static void copyInteger(byte[] value, byte[] output, int offset) {
        int first = 0;
        while (first < value.length - 1 && value[first] == 0) {
            first++;
        }
        int length = value.length - first;
        if (length > 32) {
            throw new IllegalArgumentException("ECDSA integer is too large");
        }
        System.arraycopy(value, first, output, offset + 32 - length, length);
    }

    private static void expect(byte[] bytes, int[] cursor, int expected) {
        if (cursor[0] >= bytes.length || (bytes[cursor[0]++] & 0xFF) != expected) {
            throw new IllegalArgumentException("Invalid DER signature");
        }
    }

    private static int readLength(byte[] bytes, int[] cursor) {
        int first = bytes[cursor[0]++] & 0xFF;
        if ((first & 0x80) == 0) {
            return first;
        }
        int count = first & 0x7F;
        int length = 0;
        for (int i = 0; i < count; i++) {
            length = (length << 8) | (bytes[cursor[0]++] & 0xFF);
        }
        return length;
    }

    private static byte[] sha256(byte[] input) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(input);
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text.replace("\r\n", "\n").replace('\r', '\n'), Normalizer.Form.NFC);
    }

    private static String fingerprint(byte[] publicKey) throws Exception {
        byte[] digest = sha256(publicKey);
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            if (i > 0 && i % 4 == 0) {
                value.append('-');
            }
            value.append(String.format(Locale.US, "%02X", digest[i]));
        }
        return value.toString();
    }

    public static final class Verification {
        public final boolean valid;
        public final String message;
        public final String fingerprint;
        public final long timestampSeconds;
        public final String visibleText;

        private Verification(boolean valid, String message, String fingerprint, long timestampSeconds, String visibleText) {
            this.valid = valid;
            this.message = message;
            this.fingerprint = fingerprint;
            this.timestampSeconds = timestampSeconds;
            this.visibleText = visibleText;
        }

        private static Verification invalid(String message) {
            return new Verification(false, message, "", 0L, "");
        }

        public Date signedAt() {
            return new Date(timestampSeconds * 1000L);
        }
    }
}
