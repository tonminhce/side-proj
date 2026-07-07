package vn.vnpt.checkout.api;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import vn.vnpt.checkout.domain.Checkout;
import vn.vnpt.checkout.domain.ShippingAddress;
import vn.vnpt.checkout.domain.snapshot.CartLineSnapshot;

/**
 * Tiny mapping helpers between wire DTOs and domain records — Story 2.3.
 *
 * <p>ponytail: hand-rolled mappers rather than MapStruct — one DTO per direction, no reflection.
 */
final class CheckoutMapper {

  private CheckoutMapper() {}

  static ShippingAddress toDomain(ShippingAddressDto dto) {
    if (dto == null) {
      return null;
    }
    return ShippingAddress.builder()
        .recipientName(dto.recipientName())
        .phone(dto.phone())
        .addressLine1(dto.addressLine1())
        .addressLine2(dto.addressLine2())
        .city(dto.city())
        .district(dto.district())
        .province(dto.province())
        .country(dto.country() == null ? "VN" : dto.country())
        .postalCode(dto.postalCode())
        .build();
  }

  static ShippingAddressDto toDto(ShippingAddress domain) {
    if (domain == null) {
      return null;
    }
    return new ShippingAddressDto(
        domain.getRecipientName(),
        domain.getPhone(),
        domain.getAddressLine1(),
        domain.getAddressLine2(),
        domain.getCity(),
        domain.getDistrict(),
        domain.getProvince(),
        domain.getCountry(),
        domain.getPostalCode());
  }

  static CartLineSnapshot toDomain(CartLineSnapshotDto dto) {
    if (dto == null) {
      return null;
    }
    return CartLineSnapshot.builder()
        .variantId(dto.variantId())
        .sellerId(dto.sellerId())
        .quantity(dto.quantity())
        .build();
  }

  static List<CartLineSnapshot> toDomainCartLines(List<CartLineSnapshotDto> dtos) {
    if (dtos == null || dtos.isEmpty()) {
      return List.of();
    }
    return dtos.stream().map(CheckoutMapper::toDomain).toList();
  }

  static CheckoutResponse toResponse(Checkout checkout) {
    return new CheckoutResponse(
        checkout.getUuid(),
        checkout.getCartUuid(),
        checkout.getUserId(),
        checkout.getGuestCartId(),
        checkout.getStatus() == null ? null : checkout.getStatus().name(),
        checkout.getVersion(),
        toDto(checkout.getShippingAddress()),
        checkout.getStripeClientSecret(),
        toInstant(checkout.getCreatedAt()),
        toInstant(checkout.getUpdatedAt()));
  }

  private static Instant toInstant(LocalDateTime ldt) {
    return ldt == null ? null : ldt.toInstant(ZoneOffset.UTC);
  }
}