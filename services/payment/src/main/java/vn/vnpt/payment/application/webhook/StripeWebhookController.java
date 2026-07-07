package vn.vnpt.payment.application.webhook;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.vnpt.payment.application.usecase.HandleStripeWebhookUseCase;

/**
 * Test-double Stripe webhook endpoint — Story 3.2 / FR-26 / ADR-21. Public POST {@code /webhooks/stripe}
 * that delegates to {@link HandleStripeWebhookUseCase}. The shared {@code util/web/RestExceptionHandler}
 * (commit c1b9827) translates {@link IllegalArgumentException} → {@code 400 Bad Request}.
 *
 * <p>// TODO Story 3.5: HMAC signature verification per ADR-20 (Stripe-Signature header).
 */
@RestController
@RequestMapping("/webhooks")
public class StripeWebhookController {

  private final HandleStripeWebhookUseCase handleStripeWebhookUseCase;

  public StripeWebhookController(HandleStripeWebhookUseCase handleStripeWebhookUseCase) {
    this.handleStripeWebhookUseCase = handleStripeWebhookUseCase;
  }

  @PostMapping(path = "/stripe", consumes = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> post(@RequestBody StripeWebhookEvent event) {
    HandleStripeWebhookUseCase.Outcome outcome = handleStripeWebhookUseCase.execute(event);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("received", true);
    body.put("dedup", !outcome.inserted());
    body.put("eventId", outcome.eventId());
    return ResponseEntity.ok(body);
  }
}