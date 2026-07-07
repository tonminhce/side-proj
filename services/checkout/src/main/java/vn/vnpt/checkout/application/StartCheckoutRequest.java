package vn.vnpt.checkout.application;

import java.util.List;
import lombok.Builder;
import lombok.Value;
import lombok.extern.jackson.Jacksonized;
import vn.vnpt.checkout.domain.ShippingAddress;
import vn.vnpt.checkout.domain.snapshot.CartLineSnapshot;

/**
 * Application-layer request for {@code StartCheckoutUseCase.start(...)} — Story 2.3 / FR-19.
 *
 * <p>The BFF populates {@code cartLines} from the cart snapshot retrieved via {@code GET /api/carts/\{uuid\}}
 * before calling checkout start (ponytail: simpler than a synchronous {@code CartService} HTTP call;
 * saga-driven reconciliation can replace this if snapshots drift).
 */
@Value
@Builder
@Jacksonized
public class StartCheckoutRequest {

  Long cartUuid;
  String userId;
  String guestCartId;
  ShippingAddress shippingAddress;
  List<CartLineSnapshot> cartLines;
  /** ISO 4217 currency code. Story 2.4 / FR-20 — defaults to {@code "VND"} when blank. */
  String currency;
}