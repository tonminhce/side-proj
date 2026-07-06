package vn.vnpt.util.common;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JsonPermission {
  String _id;
  String code;
  String name;
  Integer status;
  String description;
  String conversionString;
}
