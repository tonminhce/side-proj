package vn.vnpt.util.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordHasherTest {

  @Test
  void hash_returnsDifferentOutputForSameInput() {
    PasswordHasher hasher = new PasswordHasher();
    String h1 = hasher.hash("password123");
    String h2 = hasher.hash("password123");
    // Salt randomness — same input must produce different hashes.
    assertThat(h1).isNotEqualTo(h2);
  }

  @Test
  void verify_returnsTrueForCorrectPassword() {
    PasswordHasher hasher = new PasswordHasher();
    String h = hasher.hash("password123");
    assertThat(hasher.verify("password123", h)).isTrue();
    assertThat(hasher.verify("wrong", h)).isFalse();
  }

  @Test
  void verify_returnsFalseForNullEncoded() {
    PasswordHasher hasher = new PasswordHasher();
    assertThat(hasher.verify("password123", null)).isFalse();
  }

  @Test
  void verify_returnsFalseForMalformedEncoded() {
    PasswordHasher hasher = new PasswordHasher();
    assertThat(hasher.verify("password123", "not-a-valid-format")).isFalse();
    assertThat(hasher.verify("password123", "1:only-two-parts")).isFalse();
  }
}