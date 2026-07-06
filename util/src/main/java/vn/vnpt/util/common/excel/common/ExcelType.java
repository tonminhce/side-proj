package vn.vnpt.util.common.excel.common;

import java.util.EnumMap;
import org.springframework.http.MediaType;

public enum ExcelType {
  XLS,
  XLSX,
  XLSM;

  private static final EnumMap<ExcelType, MediaType> mediaTypeMap = new EnumMap<>(ExcelType.class);

  static {
    mediaTypeMap.put(ExcelType.XLS, MediaType.valueOf("application/vnd.ms-excel"));
    mediaTypeMap.put(
        ExcelType.XLSX,
        MediaType.valueOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
  }

  public MediaType getMediaType() {
    return mediaTypeMap.get(this);
  }
}
