package vn.vnpt.checkout.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** Wire shape for {@code StartCheckoutRequest} (Story 2.3 / FR-19; Story 2.4 adds currency). */
public record StartCheckoutRequestDto(
    @NotNull Long cartUuid,
    String userId,
    String guestCartId,
    @NotNull @Valid ShippingAddressDto shippingAddress,
    @NotEmpty List<CartLineSnapshotDto> cartLines,
    String currency) {}