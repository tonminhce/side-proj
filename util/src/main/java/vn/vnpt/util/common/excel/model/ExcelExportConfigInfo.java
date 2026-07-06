package vn.vnpt.util.common.excel.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExcelExportConfigInfo {

    private int startColumnIndex;

    private int startRowIndex;

    private boolean autoCreateHeader;

    private boolean autoResizeColumn;

    private boolean autoCreateSheet;

}
