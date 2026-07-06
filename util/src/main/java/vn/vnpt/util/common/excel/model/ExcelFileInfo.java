package vn.vnpt.util.common.excel.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExcelFileInfo<T> {

    private Map<String, String> headerDataMap;

    private List<T> rowDataList;

}
