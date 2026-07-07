package vn.vnpt.cart.application;

import vn.vnpt.cart.domain.Cart;

/**
 * Result of a merge — Story 2.1. {@code alreadyMerged} is true on the idempotent retry path (the
 * {@code (guestCartId, userId)} pair was already merged); false on the first successful merge.
 */
public record MergeResult(Cart targetCart, boolean alreadyMerged) {}
