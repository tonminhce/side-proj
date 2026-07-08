package vn.vnpt.auth.infrastructure.security;

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
}