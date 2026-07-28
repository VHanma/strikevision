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
    private static final byte VERSION_1 = 1;
    private static final byte VERSION_2 = 2;
    private static final byte[] DOMAIN_V1 = "GhostSign/v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] DOMAIN_V2 = "GhostSign/v2-custom-signature".getBytes(StandardCharsets.UTF_8);

    private static final String START = "\u2063\u2064\u2063\u2064";
    private static final String END = "\u2064\u2063\u2064\u2063";
    private static final char[] ALPHABET = {'\u200B', '\u200C', '\u200D', '\u2060'};
    private static final int PUBLIC_KEY_BYTES = 65;
    private static final int CRYPTO_SIGNATURE_BYTES = 64;
    private static final int V1_PAYLOAD_BYTES = 1 + 4 + 4 + PUBLIC_KEY_BYTES + CRYPTO_SIGNATURE_BYTES;
    public static final int MAX_CUSTOM_SIGNATURE_BYTES = 512;

    private GhostWatermark() {}

    public static String sign(String text) throws Exception {
        return sign(text, "");
    }

    public static String sign(String text, String customSignature) throws Exception {
        String visible = stripWatermarks(text == null ? "" : text);
        String chosen = normalize(customSignature == null ? "" : customSignature);
        byte[] chosenBytes = chosen.getBytes(StandardCharsets.UTF_8);
        if (chosenBytes.length > MAX_CUSTOM_SIGNATURE_BYTES) {
            throw new IllegalArgumentException("Hidden signature is over " + MAX_CUSTOM_SIGNATURE_BYTES + " UTF-8 bytes");
        }

        KeyPair pair = getOrCreateKeyPair();
        byte[] publicKey = publicKeyToRaw((ECPublicKey) pair.getPublic());
        int timestamp = (int) (System.currentTimeMillis() / 1000L);
        int nonce = new SecureRandom().nextInt();
        byte[] digest = sha256(normalize(visible).getBytes(StandardCharsets.UTF_8));
        byte[] signedData = signedDataV2(timestamp, nonce, chosenBytes, publicKey, digest);

        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(pair.getPrivate());
        signer.update(signedData);
        byte[] rawSignature = derToRaw(signer.sign());

        ByteBuffer payload = ByteBuffer.allocate(
                1 + 4 + 4 + 2 + chosenBytes.length + PUBLIC_KEY_BYTES + CRYPTO_SIGNATURE_BYTES);
        payload.put(VERSION_2);
        payload.putInt(timestamp);
        payload.putInt(nonce);
        payload.putShort((short) chosenBytes.length);
        payload.put(chosenBytes);
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
            if (payload.length == 0) {
                return Verification.invalid("The watermark payload is empty.");
            }
            if (payload[0] == VERSION_1) {
                return verifyV1(text, payload);
            }
            if (payload[0] == VERSION_2) {
                return verifyV2(text, payload);
            }
            return Verification.invalid("Unsupported GhostSign version.");
        } catch (Exception error) {
            return Verification.invalid("Could not decode watermark: " + error.getMessage());
        }
    }

    private static Verification verifyV1(String text, byte[] payload) throws Exception {
        if (payload.length != V1_PAYLOAD_BYTES) {
            return Verification.invalid("The legacy watermark payload is damaged.");
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.get();
        int timestamp = buffer.getInt();
        int nonce = buffer.getInt();
        byte[] publicRaw = new byte[PUBLIC_KEY_BYTES];
        byte[] signatureRaw = new byte[CRYPTO_SIGNATURE_BYTES];
        buffer.get(publicRaw);
        buffer.get(signatureRaw);

        String visible = stripWatermarks(text);
        byte[] digest = sha256(normalize(visible).getBytes(StandardCharsets.UTF_8));
        boolean valid = verifyCryptographicSignature(
                publicRaw, signatureRaw, signedDataV1(timestamp, nonce, publicRaw, digest));
        String fingerprint = fingerprint(publicRaw);
        if (!valid) {
            return new Verification(false,
                    "Signature mismatch: the visible text was changed or the watermark is damaged.",
                    fingerprint, timestamp, visible, "", VERSION_1);
        }
        return new Verification(true, "Authentic legacy GhostSign watermark.",
                fingerprint, timestamp, visible, "", VERSION_1);
    }

    private static Verification verifyV2(String text, byte[] payload) throws Exception {
        int minimum = 1 + 4 + 4 + 2 + PUBLIC_KEY_BYTES + CRYPTO_SIGNATURE_BYTES;
        if (payload.length < minimum) {
            return Verification.invalid("The watermark payload is damaged.");
        }

        ByteBuffer buffer = ByteBuffer.wrap(payload);
        buffer.get();
        int timestamp = buffer.getInt();
        int nonce = buffer.getInt();
        int chosenLength = buffer.getShort() & 0xFFFF;
        if (chosenLength > MAX_CUSTOM_SIGNATURE_BYTES
                || buffer.remaining() != chosenLength + PUBLIC_KEY_BYTES + CRYPTO_SIGNATURE_BYTES) {
            return Verification.invalid("The hidden signature length is invalid.");
        }

        byte[] chosenBytes = new byte[chosenLength];
        byte[] publicRaw = new byte[PUBLIC_KEY_BYTES];
        byte[] signatureRaw = new byte[CRYPTO_SIGNATURE_BYTES];
        buffer.get(chosenBytes);
        buffer.get(publicRaw);
        buffer.get(signatureRaw);

        String visible = stripWatermarks(text);
        byte[] digest = sha256(normalize(visible).getBytes(StandardCharsets.UTF_8));
        boolean valid = verifyCryptographicSignature(
                publicRaw,
                signatureRaw,
                signedDataV2(timestamp, nonce, chosenBytes, publicRaw, digest));
        String fingerprint = fingerprint(publicRaw);
        String customSignature = new String(chosenBytes, StandardCharsets.UTF_8);
        if (!valid) {
            return new Verification(false,
                    "Signature mismatch: the visible text or hidden signature was changed.",
                    fingerprint, timestamp, visible, customSignature, VERSION_2);
        }
        return new Verification(true, "Authentic GhostSign watermark.",
                fingerprint, timestamp, visible, customSignature, VERSION_2);
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

    public static int customSignatureByteCount(String value) {
        return normalize(value == null ? "" : value).getBytes(StandardCharsets.UTF_8).length;
    }

    private static KeyPair getOrCreateKeyPair() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) {
            PrivateKey privateKey = (PrivateKey) store.getKey(KEY_ALIAS, null);
            PublicKey publicKey = store.getCertificate(KEY_ALIAS).getPublicKey();
            return new KeyPair(publicKey, privateKey);
        }

        KeyPairGenerator generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build();
        generator.initialize(spec);
        return generator.generateKeyPair();
    }

    private static boolean verifyCryptographicSignature(
            byte[] publicRaw, byte[] signatureRaw, byte[] signedData) throws Exception {
        PublicKey publicKey = rawToPublicKey(publicRaw);
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(publicKey);
        verifier.update(signedData);
        return verifier.verify(rawToDer(signatureRaw));
    }

    private static byte[] signedDataV1(
            int timestamp, int nonce, byte[] publicKey, byte[] textDigest) {
        ByteBuffer buffer = ByteBuffer.allocate(
                DOMAIN_V1.length + 1 + 4 + 4 + publicKey.length + textDigest.length);
        buffer.put(DOMAIN_V1);
        buffer.put(VERSION_1);
        buffer.putInt(timestamp);
        buffer.putInt(nonce);
        buffer.put(publicKey);
        buffer.put(textDigest);
        return buffer.array();
    }

    private static byte[] signedDataV2(
            int timestamp, int nonce, byte[] customSignature, byte[] publicKey, byte[] textDigest) {
        ByteBuffer buffer = ByteBuffer.allocate(
                DOMAIN_V2.length + 1 + 4 + 4 + 2 + customSignature.length
                        + publicKey.length + textDigest.length);
        buffer.put(DOMAIN_V2);
        buffer.put(VERSION_2);
        buffer.putInt(timestamp);
        buffer.putInt(nonce);
        buffer.putShort((short) customSignature.length);
        buffer.put(customSignature);
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
        return Normalizer.normalize(
                text.replace("\r\n", "\n").replace('\r', '\n'),
                Normalizer.Form.NFC);
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
        public final String customSignature;
        public final int version;

        private Verification(
                boolean valid,
                String message,
                String fingerprint,
                long timestampSeconds,
                String visibleText,
                String customSignature,
                int version) {
            this.valid = valid;
            this.message = message;
            this.fingerprint = fingerprint;
            this.timestampSeconds = timestampSeconds;
            this.visibleText = visibleText;
            this.customSignature = customSignature;
            this.version = version;
        }

        private static Verification invalid(String message) {
            return new Verification(false, message, "", 0L, "", "", 0);
        }

        public Date signedAt() {
            return new Date(timestampSeconds * 1000L);
        }
    }
}
