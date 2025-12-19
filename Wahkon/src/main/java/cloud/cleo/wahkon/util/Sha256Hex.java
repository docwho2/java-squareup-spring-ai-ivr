package cloud.cleo.wahkon.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 *
 * @author sjensen
 */
public class Sha256Hex {

    /**
     *
     * @param bytes
     * @return
     */
    public static String toSha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);

            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // extremely unlikely; fallback just to avoid exploding
            return Integer.toHexString(new String(bytes, StandardCharsets.ISO_8859_1).hashCode());
        }
    }
}
