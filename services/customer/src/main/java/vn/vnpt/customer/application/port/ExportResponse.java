package vn.vnpt.customer.application.port;

import java.time.Instant;
import java.util.List;
import vn.vnpt.customer.infrastructure.entity.AddressEntity;

/** PDPD export response — Story 5.2 / FR-46. */
public record ExportResponse(
    long customerId,
    long userId,
    String displayName,
    String email,
    String phone,
    List<AddressEntity> addresses,
    Instant exportedAt) {
}