package vn.vnpt.checkout.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

/**
 * Wire shape for the checkout response — Story 2.3 / FR-19, FR-21.
 *
 * <p>The AC mandates the response carries {@code checkoutId} + {@code status: "PAYMENT_PENDING"};
 * the full DTO carries the rest for downstream consumers. Plain record — Jackson 3 natively
 * serializes records and applies {@code @JsonInclude(NON_NULL)} to strip null fields on the wire.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CheckoutResponse(
    Long checkoutId,
    Long cartUuid,
    String userId,
    String guestCartId,
    String status,
    Long version,
    ShippingAddressDto shippingAddress,
    String stripeClientSecret,
    Instant createdAt,
    Instant updatedAt) {}