package vn.vnpt.util.common.excel;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TitleInfo {
  String title;
  Integer row;
  Integer col;
}
