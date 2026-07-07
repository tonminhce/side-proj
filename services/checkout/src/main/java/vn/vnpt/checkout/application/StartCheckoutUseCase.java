package vn.vnpt.checkout.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.checkout.application.port.StripePaymentGateway;
import vn.vnpt.checkout.domain.Checkout;
import vn.vnpt.checkout.domain.CheckoutStatus;
import vn.vnpt.checkout.domain.snapshot.CartLineSnapshot;
import vn.vnpt.checkout.infrastructure.outbox.CheckoutEventPublisher;
import vn.vnpt.checkout.infrastructure.repository.CheckoutRepository;
import vn.vnpt.util.common.SnowflakeIdGenerator;

/** Story 2.3 / FR-19 (single-page checkout API); Story 2.4 / FR-20 owns the Stripe PaymentIntent. */
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class StartCheckoutUseCase {

  /** ADR-11 saga-step tag — appended to the checkoutUuid to derive the idempotency key. */
  static final String STRIPE_PAYMENT_INTENT_STEP = "stripe.payment_intent.create";

  private final CheckoutRepository checkoutRepository;
  private final CheckoutEventPublisher checkoutEventPublisher;
  private final StripePaymentGateway stripePaymentGateway;

  @Value("${checkout.stripe.currency-default:VND}")
  private String currencyDefault;

  public Checkout start(StartCheckoutRequest request) {
    validate(request);

    long amountMinor = computeAmountMinor(request.getCartLines());
    String currency = resolveCurrency(request.getCurrency());

    // Pre-generate the Snowflake ID so the Stripe idempotency key matches the persisted checkout
    // UUID (AC #4: key = (checkoutUuid, "stripe.payment_intent.create")). Pre-assigning the UUID
    // lets merge() no-op the SELECT-then-INSERT round-trip — @PrePersist keeps our value.
    Long checkoutUuid = SnowflakeIdGenerator.generateId();
    String idempotencyKey = checkoutUuid + ":" + STRIPE_PAYMENT_INTENT_STEP;
    StripePaymentGateway.Result stripeResult =
        stripePaymentGateway.createPaymentIntent(amountMinor, currency, idempotencyKey);

    Checkout checkout =
        Checkout.builder()
            .tenantId("default")
            .cartUuid(request.getCartUuid())
            .userId(request.getUserId())
            .guestCartId(request.getGuestCartId())
            .status(CheckoutStatus.PAYMENT_PENDING)
            .version(0L)
            .stripeClientSecret(stripeResult.clientSecret())
            .paymentIntentId(stripeResult.paymentIntentId())
            .shippingAddress(request.getShippingAddress())
            .build();
    // Pre-assign the Snowflake ID so the Stripe idempotency key matches the persisted UUID.
    // Lombok's @Builder does not expose superclass fields, so we use the setter.
    checkout.setUuid(checkoutUuid);

    Checkout persisted = checkoutRepository.save(checkout);

    // Same transaction as the INSERT above — ADR-04 atomicity (architecture-detail.md line 99-105).
    checkoutEventPublisher.publishCheckoutStarted(persisted, request.getCartLines());

    if (log.isDebugEnabled()) {
      log.debug(
          "Checkout started: checkout={} cart={} user={} guest={} paymentIntentId={}",
          persisted.getUuid(),
          persisted.getCartUuid(),
          persisted.getUserId(),
          persisted.getGuestCartId(),
          persisted.getPaymentIntentId());
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
    if (request.getCartLines() == null || request.getCartLines().isEmpty()) {
      throw new IllegalArgumentException("cartLines is required");
    }
  }

  /** Stripe amount for VND = đồng value (no fractional unit). Never BigDecimal/double. */
  long computeAmountMinor(java.util.List<CartLineSnapshot> lines) {
    long sum = 0L;
    for (CartLineSnapshot line : lines) {
      if (line.getUnitPriceMinor() == null || line.getQuantity() == null) {
        throw new IllegalArgumentException(
            "Each cartLine requires unitPriceMinor and quantity");
      }
      if (line.getUnitPriceMinor() < 0 || line.getQuantity() <= 0) {
        throw new IllegalArgumentException(
            "Each cartLine requires unitPriceMinor >= 0 and quantity > 0");
      }
      sum += line.getUnitPriceMinor() * line.getQuantity();
    }
    if (sum <= 0) {
      throw new IllegalArgumentException("amountMinor must be > 0");
    }
    return sum;
  }

  private String resolveCurrency(String requestCurrency) {
    String c = (requestCurrency == null || requestCurrency.isBlank()) ? currencyDefault : requestCurrency;
    if (c == null || c.isBlank()) {
      throw new IllegalArgumentException("currency must not be blank");
    }
    return c.toLowerCase(java.util.Locale.ROOT);
  }
}