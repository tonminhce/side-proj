package vn.vnpt.checkout.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.checkout.domain.Checkout;
import vn.vnpt.checkout.domain.CheckoutStatus;
import vn.vnpt.checkout.infrastructure.outbox.CheckoutEventPublisher;
import vn.vnpt.checkout.infrastructure.repository.CheckoutRepository;

/**
 * Start a new checkout — Story 2.3 / FR-19 (single-page checkout API).
 *
 * <p>Algorithm:
 * <ol>
 *   <li>Validate {@code cartUuid} non-null; either {@code userId} or {@code guestCartId} non-null;
 *       {@code shippingAddress} non-null (400 → {@code IllegalArgumentException}).
 *   <li>Persist {@link Checkout} row in {@code PAYMENT_PENDING} status (ADR-04 atomicity — same
 *       transaction as the outbox insert).
 *   <li>Emit {@code checkout.started} event via {@link CheckoutEventPublisher} — HMAC-signed
 *       (ADR-20) + cart-line snapshot for downstream consumers (saga in Story 2.5).
 *   <li>Return the persisted {@link Checkout}.
 * </ol>
 *
 * <p>{@code @Transactional} at class level satisfies the ArchUnit rule
 * {@code checkout_outboxWritesAreAtomicWithCheckoutMutation}.
 */
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class StartCheckoutUseCase {

  private final CheckoutRepository checkoutRepository;
  private final CheckoutEventPublisher checkoutEventPublisher;

  public Checkout start(StartCheckoutRequest request) {
    validate(request);

    Checkout checkout =
        Checkout.builder()
            .tenantId("default")
            .cartUuid(request.getCartUuid())
            .userId(request.getUserId())
            .guestCartId(request.getGuestCartId())
            .status(CheckoutStatus.PAYMENT_PENDING)
            .version(0L)
            .stripeClientSecret(request.getStripeClientSecret())
            .shippingAddress(request.getShippingAddress())
            .build();
    // ponytail: do NOT call setUuid() here — Hibernate treats a non-null @Id as a detached entity
    // and routes save() through merge(), which SELECTs then UPDATEs and fails because the row was
    // never inserted. BaseEntity.@PrePersist assigns the Snowflake ID during persist().

    Checkout persisted = checkoutRepository.save(checkout);

    // Same transaction as the INSERT above — ADR-04 atomicity (architecture-detail.md line 99-105).
    checkoutEventPublisher.publishCheckoutStarted(persisted, request.getCartLines());

    if (log.isDebugEnabled()) {
      log.debug(
          "Checkout started: checkout={} cart={} user={} guest={}",
          persisted.getUuid(),
          persisted.getCartUuid(),
          persisted.getUserId(),
          persisted.getGuestCartId());
    }

    return persisted;
  }

  private void validate(StartCheckoutRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("Request body is required");
    }
    if (request.getCartUuid() == null) {
      throw new IllegalArgumentException("cartUuid is required");
    }
    if ((request.getUserId() == null || request.getUserId().isBlank())
        && (request.getGuestCartId() == null || request.getGuestCartId().isBlank())) {
      throw new IllegalArgumentException("Either userId or guestCartId is required");
    }
    if (request.getShippingAddress() == null) {
      throw new IllegalArgumentException("shippingAddress is required");
    }
  }
}