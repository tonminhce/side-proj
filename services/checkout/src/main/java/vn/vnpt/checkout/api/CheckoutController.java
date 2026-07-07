package vn.vnpt.checkout.api;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.vnpt.checkout.application.GetCheckoutUseCase;
import vn.vnpt.checkout.application.StartCheckoutRequest;
import vn.vnpt.checkout.application.StartCheckoutUseCase;
import vn.vnpt.checkout.domain.Checkout;

/**
 * CheckoutService REST API — Story 2.3 / FR-19, FR-21 (ADR-09).
 *
 * <p>Service-side path {@code /api/checkouts/...}. The storefront-bff re-exposes as
 * {@code /bff/storefront/checkout/*} and owns the cookie {@code guestCartId} (httpOnly, secure,
 * sameSite=lax) + JWT auth; the checkout service receives {@code guestCartId} / {@code userId} as
 * request parameters. No Spring Security filter chain in Story 2.3 (auth is the BFF's job).
 */
@RestController
@RequestMapping("/api/checkouts")
@RequiredArgsConstructor
@Slf4j
public class CheckoutController {

  private final StartCheckoutUseCase startCheckoutUseCase;
  private final GetCheckoutUseCase getCheckoutUseCase;

  @PostMapping("/start")
  @ResponseStatus(HttpStatus.CREATED)
  public CheckoutResponse start(@RequestBody @Valid StartCheckoutRequestDto request) {
    StartCheckoutRequest applicationRequest =
        StartCheckoutRequest.builder()
            .cartUuid(request.cartUuid())
            .userId(request.userId())
            .guestCartId(request.guestCartId())
            .shippingAddress(CheckoutMapper.toDomain(request.shippingAddress()))
            .cartLines(CheckoutMapper.toDomainCartLines(request.cartLines()))
            .stripeClientSecret(request.stripeClientSecret())
            .build();

    Checkout checkout = startCheckoutUseCase.start(applicationRequest);
    return CheckoutMapper.toResponse(checkout);
  }

  @GetMapping("/{uuid}")
  public ResponseEntity<CheckoutResponse> findByUuid(@PathVariable Long uuid) {
    Checkout checkout = getCheckoutUseCase.findByCheckoutUuid(uuid);
    return ResponseEntity.ok(CheckoutMapper.toResponse(checkout));
  }
}