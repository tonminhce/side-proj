package vn.vnpt.checkout.infrastructure.security;

/** HMAC key provider interface — Story 3.5 follow-up. */
public interface HmacServiceKeyProvider {
  String currentSecret();
}