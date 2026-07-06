package vn.vnpt.util.common.excel.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import vn.vnpt.util.annotation.SpecialSymbolConstraint;
import vn.vnpt.util.common.excel.enumdef.CellTypeEnum;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExcelFieldInfo {

    private int colIndex;

    @SpecialSymbolConstraint
    private String colName;

    @SpecialSymbolConstraint
    private String colLabel;

    private boolean lineField;

    private CellTypeEnum type;

}
