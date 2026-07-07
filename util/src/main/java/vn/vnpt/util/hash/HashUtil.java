package vn.vnpt.util.hash;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 hex and JWT-style arbitrary digests. See ADR-11 / cart MergeKeyUtil §AC #20. */
public final class HashUtil {

  private HashUtil() {}

  public static String sha256Hex(String input) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 mandated by JRE", e);
    }
  }

  public static String sha256Hex(String a, String sep, String b) {
    return sha256Hex(a + sep + b);
  }
}
