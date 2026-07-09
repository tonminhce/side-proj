package vn.vnpt.customer.application.web;

import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.vnpt.customer.application.port.AddAddressCommand;
import vn.vnpt.customer.application.port.CreateCustomerCommand;
import vn.vnpt.customer.application.port.ExportResponse;
import vn.vnpt.customer.application.port.ForgetResponse;
import vn.vnpt.customer.application.usecase.AddAddressUseCase;
import vn.vnpt.customer.application.usecase.AddressAutocompleteUseCase;
import vn.vnpt.customer.application.usecase.AddressAutocompleteUseCase.Type;
import vn.vnpt.customer.application.usecase.CreateCustomerUseCase;
import vn.vnpt.customer.application.usecase.ExportCustomerDataUseCase;
import vn.vnpt.customer.application.usecase.ForgetCustomerUseCase;
import vn.vnpt.customer.application.usecase.GetCustomerWithAddressesUseCase;
import vn.vnpt.customer.domain.Customer;

/**
 * Customer REST controller — Story 5.1 + 5.2 + 5.3. Endpoints:
 *   - POST /api/customers (create)
 *   - GET  /api/customers/{id} (with addresses)
 *   - POST /api/customers/{id}/addresses (add address)
 *   - GET  /api/customers/{id}/addresses (list addresses)
 *   - GET  /api/customers/{id}/export (PDPD data export)
 *   - POST /api/customers/{id}/forget (right-to-be-forgotten)
 *   - GET  /api/customers/addresses/autocomplete (Vietnamese address autocomplete)
 */
@RestController
@RequestMapping("/api/customers")
public class CustomerController {

  private final CreateCustomerUseCase createUseCase;
  private final AddAddressUseCase addAddressUseCase;
  private final GetCustomerWithAddressesUseCase getUseCase;
  private final ExportCustomerDataUseCase exportUseCase;
  private final ForgetCustomerUseCase forgetUseCase;
  private final AddressAutocompleteUseCase autocompleteUseCase;

  public CustomerController(
      CreateCustomerUseCase createUseCase,
      AddAddressUseCase addAddressUseCase,
      GetCustomerWithAddressesUseCase getUseCase,
      ExportCustomerDataUseCase exportUseCase,
      ForgetCustomerUseCase forgetUseCase,
      AddressAutocompleteUseCase autocompleteUseCase) {
    this.createUseCase = createUseCase;
    this.addAddressUseCase = addAddressUseCase;
    this.getUseCase = getUseCase;
    this.exportUseCase = exportUseCase;
    this.forgetUseCase = forgetUseCase;
    this.autocompleteUseCase = autocompleteUseCase;
  }

  @PostMapping
  public ResponseEntity<Map<String, Object>> create(@RequestBody CreateCustomerCommand cmd) {
    Customer c = createUseCase.execute(cmd);
    return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
        "id", c.id(),
        "userId", c.userId(),
        "displayName", c.displayName(),
        "email", c.email() == null ? "" : c.email(),
        "phone", c.phone() == null ? "" : c.phone()));
  }

  @GetMapping("/{id}")
  public ResponseEntity<Map<String, Object>> get(@PathVariable long id) {
    Customer c = getUseCase.execute(id);
    return ResponseEntity.ok(Map.of(
        "id", c.id(),
        "userId", c.userId(),
        "displayName", c.displayName(),
        "email", c.email() == null ? "" : c.email(),
        "phone", c.phone() == null ? "" : c.phone(),
        "addresses", c.addresses().stream().map(AddressDto::fromEntity).toList()));
  }

  @PostMapping("/{id}/addresses")
  public ResponseEntity<Map<String, Object>> addAddress(
      @PathVariable long id,
      @RequestBody AddAddressRequest body) {
    long addrId = addAddressUseCase.execute(new AddAddressCommand(
        id, body.line1(), body.provinceCode(), body.districtCode(), body.communeCode(),
        body.isDefault()));
    return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
        "addressId", addrId,
        "customerId", id));
  }

  @GetMapping("/{id}/addresses")
  public List<AddressDto> listAddresses(@PathVariable long id) {
    Customer c = getUseCase.execute(id);
    return c.addresses().stream().map(AddressDto::fromEntity).toList();
  }

  /** Story 5.2 / FR-46 — PDPD data export. */
  @GetMapping("/{id}/export")
  public ResponseEntity<ExportResponse> export(@PathVariable long id) {
    return ResponseEntity.ok(exportUseCase.execute(id));
  }

  /** Story 5.2 / FR-49 — right-to-be-forgotten. */
  @PostMapping("/{id}/forget")
  public ResponseEntity<ForgetResponse> forget(@PathVariable long id) {
    return ResponseEntity.ok(forgetUseCase.execute(id));
  }

  /** Story 5.3 / FR-48 — Vietnamese address autocomplete. */
  @GetMapping("/addresses/autocomplete")
  public ResponseEntity<List<?>> autocomplete(
      @RequestParam("type") String type,
      @RequestParam("q") String q,
      @RequestParam(value = "parent", required = false) String parent) {
    Type parsedType;
    try {
      parsedType = Type.valueOf(type.toUpperCase());
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(List.of());
    }
    if ((parsedType == Type.DISTRICT || parsedType == Type.COMMUNE)
        && (parent == null || parent.isBlank())) {
      return ResponseEntity.badRequest().body(List.of());
    }
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(3600, java.util.concurrent.TimeUnit.SECONDS).cachePublic())
        .body(autocompleteUseCase.execute(parsedType, q, parent));
  }

  public record AddAddressRequest(
      String line1, String provinceCode, String districtCode, String communeCode, boolean isDefault) {}
}