package vn.vnpt.order.infrastructure.security;

/**
 * HMAC service key provider — Story 4.1. Two implementations: Vault-backed for non-dev profiles
 * + dev-only env-var fallback. Mirrors Story 3.5's pattern; per-service key isolation
 * (this one is for the {@code order} service).
 */
public interface HmacServiceKeyProvider {
  String currentSecret();
}