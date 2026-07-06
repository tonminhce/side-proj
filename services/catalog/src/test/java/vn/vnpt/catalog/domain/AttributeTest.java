package vn.vnpt.catalog.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Pure-JUnit test for {@link Attribute} (Lombok @Builder sanity). Subtask 9.3. */
class AttributeTest {

  @Test
  void builder_setsAllFields() {
    Attribute attribute =
        Attribute.builder()
            .productUuid(7L)
            .name("color")
            .displayName("Color")
            .sortOrder(2)
            .build();
    assertThat(attribute.getProductUuid()).isEqualTo(7L);
    assertThat(attribute.getName()).isEqualTo("color");
    assertThat(attribute.getDisplayName()).isEqualTo("Color");
    assertThat(attribute.getSortOrder()).isEqualTo(2);
  }
}
