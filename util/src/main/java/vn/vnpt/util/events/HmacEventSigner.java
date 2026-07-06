package vn.vnpt.util.events;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA-256 event signer for the per-service event-signing contract (ADR-20 / AT-03).
 *
 * <p>Why JDK stdlib only: {@code HmacSHA256} is mandated by the JRE, every conformant JDK ships it,
 * and pulling in BouncyCastle or Apache Commons Codec is a 1MB+ dependency for two 5-line
 * operations. The {@code catch} clauses are defensive — if the algorithm is missing the JVM is
 * non-conformant and there's no recovery.
 *
 * <p>Output encoding: base64url WITHOUT padding, per RFC 4648 §5 (URL- and filename-safe). The
 * decoder accepts the same format. Wire format is the same envelope in {@link JcsCanonicalJson}.
 *
 * <p>Constant-time compare: {@link MessageDigest#isEqual(byte[], byte[])} is constant-time on JDK
 * 7+ (it XORs in fixed-length chunks before comparing). {@code Arrays.equals} and {@code
 * String.equals} leak timing; do NOT use them for HMAC comparison.
 */
public final class HmacEventSigner {

  private HmacEventSigner() {}

  /**
   * Compute the base64url-encoded HMAC-SHA-256 of {@code canonicalJson} under {@code
   * serviceSecret}.
   *
   * @throws IllegalStateException if the JRE does not provide {@code HmacSHA256} or the key is
   *     rejected (the latter is unreachable for UTF-8 byte arrays; defensive only).
   */
  public static String sign(String canonicalJson, String serviceSecret) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(serviceSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] sig = mac.doFinal(canonicalJson.getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException(
          "HMAC-SHA256 unavailable in JDK — security primitive failed", e);
    }
  }

  /**
   * Constant-time verification of a base64url-encoded HMAC-SHA-256 signature.
   *
   * @return {@code true} iff the recomputed signature matches the supplied one
   */
  public static boolean verify(String canonicalJson, String signatureB64Url, String serviceSecret) {
    String expected = sign(canonicalJson, serviceSecret);
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8),
        signatureB64Url.getBytes(StandardCharsets.UTF_8));
  }
}
