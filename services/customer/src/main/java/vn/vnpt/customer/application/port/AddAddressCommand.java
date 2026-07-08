package vn.vnpt.customer.application.port;

/** Trust-boundary-validated command to add an address — Story 5.1. */
public record AddAddressCommand(
    long customerId,
    String line1,
    String provinceCode,
    String districtCode,
    String communeCode,
    boolean isDefault) {

  public AddAddressCommand {
    if (customerId <= 0) {
      throw new IllegalArgumentException("customerId must be positive");
    }
    if (line1 == null || line1.isBlank()) {
      throw new IllegalArgumentException("line1 must not be null or blank");
    }
    if (provinceCode == null || provinceCode.isBlank()) {
      throw new IllegalArgumentException("provinceCode must not be null or blank");
    }
    if (districtCode == null || districtCode.isBlank()) {
      throw new IllegalArgumentException("districtCode must not be null or blank");
    }
    if (communeCode == null || communeCode.isBlank()) {
      throw new IllegalArgumentException("communeCode must not be null or blank");
    }
  }
}