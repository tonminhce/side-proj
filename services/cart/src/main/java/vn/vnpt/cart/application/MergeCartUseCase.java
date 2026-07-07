package vn.vnpt.cart.application;

import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.cart.domain.Cart;
import vn.vnpt.cart.domain.CartLine;
import vn.vnpt.cart.domain.CartMergeLog;
import vn.vnpt.cart.domain.CartStatus;
import vn.vnpt.cart.domain.exception.AnonymousCartOwnershipConflictException;
import vn.vnpt.cart.infrastructure.outbox.CartEventPublisher;
import vn.vnpt.cart.infrastructure.repository.CartLineRepository;
import vn.vnpt.cart.infrastructure.repository.CartMergeLogRepository;
import vn.vnpt.cart.infrastructure.repository.CartRepository;

/**
 * MergeCartUseCase — Story 2.1 / FR-14, NFR-IDEM-3 (AC #6).
 *
 * <p>Idempotent on {@code (guestCartId, userId)} via {@code cart_merge_log.idempotency_key =
 * sha256(guestCartId + ":" + userId)} (the DB UNIQUE constraint is the beacon). Merge mechanics:
 * transfer anonymous lines to the user-bound cart (summing quantities for shared variants), mark the
 * source cart {@code MERGED}, record the merge log, and emit the signed {@code cart.merged} event —
 * all in one {@code @Transactional} boundary (ADR-04 atomicity).
 *
 * <p>ponytail: AC #6 step 4 describes a compensate-delete when the anonymous cart is missing; we
 * instead record a 0-line merge log so retries are truly idempotent (simpler than the delete dance,
 * same observable result — the retry returns the same target cart).
 */
@Service
@Transactional
@RequiredArgsConstructor
@Slf4j
public class MergeCartUseCase {

  private static final String DEFAULT_TENANT = "default";

  private final CartRepository cartRepository;
  private final CartLineRepository cartLineRepository;
  private final CartMergeLogRepository cartMergeLogRepository;
  private final GetOrCreateCartUseCase getOrCreateCartUseCase;
  private final CartEventPublisher cartEventPublisher;

  public MergeResult merge(String guestCartId, String userId) {
    if (guestCartId == null || guestCartId.isBlank() || userId == null || userId.isBlank()) {
      throw new IllegalArgumentException("both guestCartId and userId are required");
    }
    String idempotencyKey = MergeKeyUtil.sha256(guestCartId, userId);

    // Idempotent retry — same (guestCartId, userId) already merged.
    var existing = cartMergeLogRepository.findByIdempotencyKey(idempotencyKey);
    if (existing.isPresent()) {
      Cart target = getOrCreateCartUseCase.getOrCreate(null, userId);
      return new MergeResult(target, true);
    }

    // Ownership conflict — a DIFFERENT user already claimed this anonymous cart (AC #6, 409).
    for (CartMergeLog log : cartMergeLogRepository.findByGuestCartId(guestCartId)) {
      if (!userId.equals(log.getUserId())) {
        throw new AnonymousCartOwnershipConflictException(guestCartId, log.getUserId());
      }
    }

    Cart target = getOrCreateCartUseCase.getOrCreate(null, userId);
    Cart source =
        cartRepository
            .findByTenantIdAndGuestCartIdAndStatus(
                DEFAULT_TENANT, guestCartId, CartStatus.ANONYMOUS)
            .orElse(null);

    int mergedLines = 0;
    if (source != null) {
      List<CartLine> sourceLines = cartLineRepository.findByCartUuid(source.getUuid());
      for (CartLine srcLine : sourceLines) {
        if (Boolean.TRUE.equals(srcLine.getIsDeleted())) {
          continue;
        }
        cartLineRepository
            .findByCartUuidAndVariantId(target.getUuid(), srcLine.getVariantId())
            .ifPresentOrElse(
                targetLine -> {
                  // Same variant → sum quantities (Baymard guest-cart merge UX).
                  targetLine.setQuantity(targetLine.getQuantity() + srcLine.getQuantity());
                  cartLineRepository.save(targetLine);
                },
                () ->
                    cartLineRepository.save(
                        CartLine.builder()
                            .cartUuid(target.getUuid())
                            .tenantId(DEFAULT_TENANT)
                            .sellerId(srcLine.getSellerId())
                            .variantId(srcLine.getVariantId())
                            .quantity(srcLine.getQuantity())
                            .build()));
        mergedLines++;
      }
      source.setStatus(CartStatus.MERGED);
      cartRepository.save(source);
    }

    try {
      cartMergeLogRepository.save(
          CartMergeLog.builder()
              .tenantId(DEFAULT_TENANT)
              .idempotencyKey(idempotencyKey)
              .guestCartId(guestCartId)
              .userId(userId)
              .sourceCartUuid(source != null ? source.getUuid() : null)
              .targetCartUuid(target.getUuid())
              .mergedLinesCount(mergedLines)
              .build());
    } catch (DataIntegrityViolationException e) {
      // Concurrent merge of the same pair won the race (UNIQUE on idempotency_key) — treat as retry.
      log.debug("merge: idempotency_key race for {}; returning target as retry", idempotencyKey);
      Cart target2 = getOrCreateCartUseCase.getOrCreate(null, userId);
      return new MergeResult(target2, true);
    }

    if (source != null) {
      cartEventPublisher.publishCartMerged(source, target, mergedLines);
    }
    return new MergeResult(target, false);
  }
}
