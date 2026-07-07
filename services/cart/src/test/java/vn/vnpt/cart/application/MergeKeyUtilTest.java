package vn.vnpt.cart.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MergeKeyUtilTest {

  @Test
  void sha256_returnsStableHexForSameInputs() {
    String a = MergeKeyUtil.sha256("guest-1", "user-1");
    String b = MergeKeyUtil.sha256("guest-1", "user-1");
    assertThat(a).isEqualTo(b);
    assertThat(a).hasSize(64).matches("[0-9a-f]+");
  }

  @Test
  void sha256_returnsDifferentHexForDifferentInputs() {
    assertThat(MergeKeyUtil.sha256("guest-1", "user-1"))
        .isNotEqualTo(MergeKeyUtil.sha256("guest-1", "user-2"));
  }
}
