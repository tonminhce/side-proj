package vn.vnpt.checkout.api;

/**
 * Wire shape for {@code ShippingAddress} (Story 2.3 / FR-19).
 *
 * <p>Plain record — Jackson 3 deserializes records natively (no {@code @Jacksonized} required).
 */
public record ShippingAddressDto(
    String recipientName,
    String phone,
    String addressLine1,
    String addressLine2,
    String city,
    String district,
    String province,
    String country,
    String postalCode) {}