package vn.vnpt.util.common.excel;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.poi.ss.usermodel.Row;

@NoArgsConstructor
@AllArgsConstructor
@Data
public class ExcelData {
  Object beforeData;
  Object currentData;
  Object afterData;
  Row row;
  Class<?> type;
  Integer startMergeIndex;
  Integer endMergeIndex;
  Integer currentIndex;
}
