package vn.vnpt.payment.application.port;

/**
 * Risk classification of the buyer / transaction — Story 3.5 follow-up / FR-27.
 *
 * <p>The 3DS step-up decision (see {@link ThreeDSecureDecision}) consumes this signal alongside
 * the issuer country (EEA vs non-EEA) and the amount (>= 30 EUR equivalent per PSD2 RTS Article
 * 18 low-value exemption floor). Lower {@link #LOW} means skip 3DS; {@link #ELEVATED} and
 * {@link #HIGHEST} require step-up for EEA transactions.
 *
 * <p>The enum intentionally has no value above {@code HIGHEST}; that ceiling is enforced by the
 * stripe-java enum mapping in {@link vn.vnpt.payment.infrastructure.stripe.RealStripePaymentAdapter}
 * (any future {@code CRITICAL} tier would need a fresh decision branch).
 */
public enum RiskLevel {
  LOW,
  ELEVATED,
  HIGHEST
}