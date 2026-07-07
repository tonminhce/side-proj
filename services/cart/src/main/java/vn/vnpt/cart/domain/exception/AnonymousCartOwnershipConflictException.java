package vn.vnpt.cart.domain.exception;

/**
 * Raised when a user tries to merge an anonymous cart that already belongs to a different user
 * (Story 2.1 / FR-16, AC #6). Maps to HTTP 409 with the owning user id.
 */
public class AnonymousCartOwnershipConflictException extends RuntimeException {

  private final String guestCartId;
  private final String ownerUserId;

  public AnonymousCartOwnershipConflictException(String guestCartId, String ownerUserId) {
    super("Anonymous cart " + guestCartId + " belongs to a different user");
    this.guestCartId = guestCartId;
    this.ownerUserId = ownerUserId;
  }

  public String getGuestCartId() {
    return guestCartId;
  }

  public String getOwnerUserId() {
    return ownerUserId;
  }
}
