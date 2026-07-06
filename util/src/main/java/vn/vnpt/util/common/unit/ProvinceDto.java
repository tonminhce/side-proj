package vn.vnpt.util.common.unit;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ProvinceDto {

  private String uuid;
  private String provinceName;
  private String provinceCode;
}
