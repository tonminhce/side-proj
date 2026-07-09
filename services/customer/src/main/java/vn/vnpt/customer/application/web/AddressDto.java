package vn.vnpt.customer.application.web;

import vn.vnpt.customer.infrastructure.entity.AddressEntity;

/** Address response DTO — Story 5.1 / FR-47. Explicit fields so the controller never serializes
 *  the JPA entity (which would expose the lazy @ManyToOne Customer and risk LazyInitializationException
 *  with open-in-view: false). */
public record AddressDto(
    long id,
    String line1,
    String provinceCode,
    String districtCode,
    String communeCode,
    boolean isDefault) {

  public static AddressDto fromEntity(AddressEntity a) {
    return new AddressDto(
        a.getId(),
        a.getLine1(),
        a.getProvinceCode(),
        a.getDistrictCode(),
        a.getCommuneCode(),
        a.isDefault());
  }
}