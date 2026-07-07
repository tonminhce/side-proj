package vn.vnpt.checkout.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;

/**
 * Shipping address value object — Story 2.3 / FR-19.
 *
 * <p>Embedded into {@link Checkout} as {@code @Embedded}. {@code @Embeddable} so JPA flattens the
 * fields into the {@code checkouts} row; {@code @Value @Builder @Jacksonized} for the JSON wire shape
 * (carried in {@code CheckoutStartedEvent}).
 */
@Embeddable
@Value
@Builder
@Jacksonized
@NoArgsConstructor(force = true, access = AccessLevel.PRIVATE)
@AllArgsConstructor
@Getter
@Setter
public class ShippingAddress {

  @Column(name = "recipient_name", nullable = false, length = 255)
  String recipientName;

  @Column(name = "phone", nullable = false, length = 32)
  String phone;

  @Column(name = "address_line_1", nullable = false, length = 512)
  String addressLine1;

  @Column(name = "address_line_2", length = 512)
  String addressLine2;

  @Column(name = "city", nullable = false, length = 128)
  String city;

  @Column(name = "district", length = 128)
  String district;

  @Column(name = "province", nullable = false, length = 128)
  String province;

  @Column(name = "country", nullable = false, length = 2)
  String country;

  @Column(name = "postal_code", length = 16)
  String postalCode;
}