package vn.vnpt.util.common.excel.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExcelImportConfigInfo {

    private int sheetIndex;

    private int startColumnIndex;

    private int headerRowIndex;

    private int dataStartRowIndex;

}
