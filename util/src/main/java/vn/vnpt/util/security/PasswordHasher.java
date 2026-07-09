package vn.vnpt.util.security;

import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import org.springframework.stereotype.Component;

/** PBKDF2 password hasher — Story 5.4 / FR-73. Moved to util per F1 deep-review rule
 *  (shared code lives in util/, no auth-specific deps). Format: {@code <iterations>:<saltB64>:<hashB64>}.
 *  600_000 iterations + 16-byte random salt per OWASP 2024 password-storage cheat sheet
 *  (PBKDF2-HMAC-SHA256 minimum). Future story swaps to Argon2id.
 *  <p>Spring DI: kept {@code @Component} so existing use cases inject it transparently; auth
 *  and customer service (future password flow) both get the helper. */
@Component
public class PasswordHasher {

  private static final int ITERATIONS = 600_000;
  private static final int SALT_BYTES = 16;
  private static final int HASH_BITS = 256;
  private static final String ALGO = "PBKDF2WithHmacSHA256";

  public String hash(String password) {
    byte[] salt = new byte[SALT_BYTES];
    new SecureRandom().nextBytes(salt);
    byte[] hash = pbkdf2(password.toCharArray(), salt, ITERATIONS, HASH_BITS);
    return ITERATIONS + ":" + Base64.getEncoder().encodeToString(salt) + ":" + Base64.getEncoder().encodeToString(hash);
  }

  public boolean verify(String password, String encoded) {
    if (encoded == null) return false;
    String[] parts = encoded.split(":");
    if (parts.length != 3) return false;
    int iters = Integer.parseInt(parts[0]);
    byte[] salt = Base64.getDecoder().decode(parts[1]);
    byte[] expected = Base64.getDecoder().decode(parts[2]);
    byte[] actual = pbkdf2(password.toCharArray(), salt, iters, expected.length * 8);
    return java.security.MessageDigest.isEqual(expected, actual);
  }

  private static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int bits) {
    try {
      PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, bits);
      SecretKeyFactory skf = SecretKeyFactory.getInstance(ALGO);
      return skf.generateSecret(spec).getEncoded();
    } catch (Exception e) {
      throw new IllegalStateException("PBKDF2 unavailable", e);
    }
  }
}