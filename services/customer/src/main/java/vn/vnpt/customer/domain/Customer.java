package vn.vnpt.customer.domain;

import java.util.List;
import vn.vnpt.customer.infrastructure.entity.AddressEntity;

/** Customer aggregate root — Story 5.1. */
public record Customer(
    long id,
    long userId,
    String displayName,
    String email,
    String phone,
    List<AddressEntity> addresses) {
}