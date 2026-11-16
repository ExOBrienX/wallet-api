package cl.exobrienx.wallet.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Hashing {

    private Hashing() {
    }

    /** SHA-256 del texto (UTF-8) en hexadecimal: 64 caracteres. */
    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // Todo JRE está obligado a implementar SHA-256; si falta, el entorno está roto.
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
