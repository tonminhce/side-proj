package vn.vnpt.customer.application.port;

import java.time.Instant;

/** Right-to-be-forgotten response — Story 5.2 / FR-49. */
public record ForgetResponse(long customerId, Instant forgottenAt) {
}