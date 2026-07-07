package vn.vnpt.cart.application;

import vn.vnpt.util.hash.HashUtil;

/** Backwards-compat shim — see util/hash/HashUtil. */
public final class MergeKeyUtil {

  private MergeKeyUtil() {}

  public static String sha256(String guestCartId, String userId) {
    return HashUtil.sha256Hex(guestCartId, ":", userId);
  }
}
