package vn.vnpt.cart.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 idempotency-key helper for the merge endpoint — Story 2.1 / AC #20.
 *
 * <p>ponytail: util does NOT yet expose a hash util; this 3-line helper is acceptable. A future
 * hardening story may promote it to {@code util/hash/HashUtil} if 3+ services need it (cart + future
 * SearchService query normalization + payment webhook signing) — wait for the second user before
 * extracting.
 */
public final class MergeKeyUtil {

  private MergeKeyUtil() {}

  /** {@code sha256(guestCartId + ":" + userId)} as lowercase hex — the ADR-11 idempotency key. */
  public static String sha256(String guestCartId, String userId) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest((guestCartId + ":" + userId).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
