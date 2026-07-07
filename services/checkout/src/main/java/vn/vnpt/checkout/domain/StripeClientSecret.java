package vn.vnpt.checkout.domain;

import lombok.Value;

/**
 * Stripe client secret value object — Story 2.3 / FR-20 forward-compat (Story 2.4 populates).
 *
 * <p>Story 2.3 stores the value the BFF passes through but does NOT create a Stripe PaymentIntent;
 * the column is reserved in V001 for forward compatibility. {@code clientSecret} is the BFF-supplied
 * value (nullable); {@code paymentIntentId} is null until Story 2.4.
 */
@Value
public class StripeClientSecret {

  String clientSecret;
  String paymentIntentId;
}