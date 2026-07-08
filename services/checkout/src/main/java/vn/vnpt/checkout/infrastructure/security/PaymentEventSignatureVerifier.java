package vn.vnpt.checkout.infrastructure.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vn.vnpt.util.events.HmacEventSigner;

/**
 * Verifies HMAC signatures on incoming payment events — Story 3.5 follow-up / FR-82.
 * The verification uses {@link HmacEventSigner#verify} (constant-time compare via
 * {@code MessageDigest.isEqual}).
 *
 * <p>// TODO ops: the actual envelope (header + payload + signature) lands in the
 * in-process event record (via Spring Modulith's {@code ApplicationModuleListener} delivery);
 * v1 ships the verifier + the metric; the saga listener wiring lands with a follow-up
 * story (the actual call site is in the saga's onPaymentCaptured path).
 */
@Component
public class PaymentEventSignatureVerifier {

  private static final Logger log = LoggerFactory.getLogger(PaymentEventSignatureVerifier.class);

  private final HmacServiceKeyProvider keyProvider;
  private final Counter mismatchCounter;
  private final Counter errorCounter;

  public PaymentEventSignatureVerifier(HmacServiceKeyProvider keyProvider,
                                       MeterRegistry meterRegistry) {
    this.keyProvider = keyProvider;
    this.mismatchCounter = Counter.builder("security.event.signature.mismatch")
        .tag("producer", "payment")
        .tag("consumer", "checkout")
        .register(meterRegistry);
    this.errorCounter = Counter.builder("security.event.signature.error")
        .tag("consumer", "checkout")
        .register(meterRegistry);
  }

  public boolean verify(String canonicalJson, String signatureB64Url) {
    if (canonicalJson == null || signatureB64Url == null) {
      errorCounter.increment();
      return false;
    }
    try {
      boolean ok = HmacEventSigner.verify(canonicalJson, signatureB64Url, keyProvider.currentSecret());
      if (!ok) {
        mismatchCounter.increment();
        // R-15: log carries only the event name + producer + high-level reason.
        // NO canonical JSON / NO signature / NO secret in the log line.
        log.warn("HMAC signature mismatch on payment event (producer=payment); rejecting event");
      }
      return ok;
    } catch (RuntimeException e) {
      errorCounter.increment();
      log.error("HMAC verification error: {}", e.getMessage());
      return false;
    }
  }
}