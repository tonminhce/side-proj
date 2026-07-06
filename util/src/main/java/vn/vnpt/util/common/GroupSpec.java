package vn.vnpt.util.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class GroupSpec {
  boolean enabled;
  String keyField;
  String titleField;
  String
      titleFormat; // => có thể điều chỉnh text của title xã ví dụ: "Xã %s [Thông tin xã/phường có
  // Mã số vùng trồng, các MSVT bên dưới sẽ gom theo xã]"
  Integer fromRow = 1;
  Integer fromCol = 1;
  Integer toCol = null;
}
