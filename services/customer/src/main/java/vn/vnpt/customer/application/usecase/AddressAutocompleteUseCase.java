package vn.vnpt.customer.application.usecase;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.vnpt.customer.domain.VnAddressCatalog;
import vn.vnpt.util.common.unit.CommuneDto;
import vn.vnpt.util.common.unit.DistrictDto;
import vn.vnpt.util.common.unit.ProvinceDto;

/**
 * Address autocomplete use case — Story 5.3 / FR-48. Delegates to the in-memory catalog.
 */
@Service
@Transactional(readOnly = true)
public class AddressAutocompleteUseCase {

  public enum Type { PROVINCE, DISTRICT, COMMUNE }

  private final VnAddressCatalog catalog;

  public AddressAutocompleteUseCase(VnAddressCatalog catalog) {
    this.catalog = catalog;
  }

  public List<?> execute(Type type, String q, String parent) {
    return switch (type) {
      case PROVINCE -> catalog.suggestProvinces(q);
      case DISTRICT -> catalog.suggestDistricts(parent, q);
      case COMMUNE -> catalog.suggestCommunes(parent, q);
    };
  }

  public List<ProvinceDto> provinces(String q) {
    return catalog.suggestProvinces(q);
  }
  public List<DistrictDto> districts(String parent, String q) {
    return catalog.suggestDistricts(parent, q);
  }
  public List<CommuneDto> communes(String parent, String q) {
    return catalog.suggestCommunes(parent, q);
  }
}