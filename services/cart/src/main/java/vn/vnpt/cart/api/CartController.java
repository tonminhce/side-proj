package vn.vnpt.cart.api;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import vn.vnpt.cart.application.AddLineUseCase;
import vn.vnpt.cart.application.GetOrCreateCartUseCase;
import vn.vnpt.cart.application.MergeCartUseCase;
import vn.vnpt.cart.application.MergeResult;
import vn.vnpt.cart.application.RemoveLineUseCase;
import vn.vnpt.cart.application.UpdateLineQuantityUseCase;
import vn.vnpt.cart.domain.Cart;
import vn.vnpt.cart.domain.CartLine;
import vn.vnpt.cart.infrastructure.repository.CartLineRepository;

/**
 * CartService REST API — Story 2.1 / FR-14, FR-15, FR-16 (ADR-09).
 *
 * <p>Service-side path {@code /api/carts/...}. The storefront-bff re-exposes as
 * {@code /bff/storefront/cart/*} and owns the cookie {@code guestCartId} (httpOnly, secure,
 * sameSite=lax) + JWT auth; the cart service receives {@code guestCartId} / {@code userId} as
 * request parameters. No Spring Security filter chain in Story 2.1 (auth is the BFF's job).
 */
@RestController
@RequestMapping("/api/carts")
@RequiredArgsConstructor
public class CartController {

  private final GetOrCreateCartUseCase getOrCreateCartUseCase;
  private final AddLineUseCase addLineUseCase;
  private final UpdateLineQuantityUseCase updateLineQuantityUseCase;
  private final RemoveLineUseCase removeLineUseCase;
  private final MergeCartUseCase mergeCartUseCase;
  // Read-only line fetch for building responses (cart total computed on read, AC #3).
  private final CartLineRepository cartLineRepository;

  @GetMapping("/{uuid}")
  public CartResponse getCart(@PathVariable Long uuid) {
    Cart cart = getOrCreateCartUseCase.findByUuid(uuid);
    return CartResponse.from(cart, linesOf(uuid));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public CartResponse createCart(@RequestBody CreateCartRequest req) {
    Cart cart = getOrCreateCartUseCase.getOrCreate(req.guestCartId(), req.userId());
    return CartResponse.from(cart, linesOf(cart.getUuid()));
  }

  @PostMapping("/{uuid}/lines")
  @ResponseStatus(HttpStatus.CREATED)
  public CartResponse addLine(@PathVariable Long uuid, @RequestBody AddLineRequest req) {
    Cart cart =
        addLineUseCase.addLine(uuid, req.variantId(), req.quantity(), req.expectedCartVersion());
    return CartResponse.from(cart, linesOf(uuid));
  }

  @PatchMapping("/{uuid}/lines/{lineUuid}")
  public CartResponse updateLine(
      @PathVariable Long uuid,
      @PathVariable Long lineUuid,
      @RequestBody UpdateLineQuantityRequest req) {
    updateLineQuantityUseCase.updateQuantity(
        uuid, lineUuid, req.quantity(), req.expectedLineVersion());
    Cart cart = getOrCreateCartUseCase.findByUuid(uuid);
    return CartResponse.from(cart, linesOf(uuid));
  }

  @DeleteMapping("/{uuid}/lines/{lineUuid}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void removeLine(
      @PathVariable Long uuid,
      @PathVariable Long lineUuid,
      @RequestParam(required = false) Long expectedCartVersion) {
    removeLineUseCase.removeLine(uuid, lineUuid, expectedCartVersion);
  }

  @PostMapping("/merge")
  public ResponseEntity<CartResponse> merge(@RequestBody MergeRequest req) {
    MergeResult result = mergeCartUseCase.merge(req.guestCartId(), req.userId());
    Cart target = result.targetCart();
    return ResponseEntity.ok(CartResponse.from(target, linesOf(target.getUuid())));
  }

  private List<CartLine> linesOf(Long cartUuid) {
    return cartLineRepository.findByCartUuid(cartUuid);
  }
}
