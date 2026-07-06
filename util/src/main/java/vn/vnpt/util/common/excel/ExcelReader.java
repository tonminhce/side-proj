package vn.vnpt.util.common.excel;

import java.io.IOException;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.Map.Entry;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import vn.vnpt.util.annotation.ExcelImport;
import vn.vnpt.util.annotation.ExcelImportConfig;
import vn.vnpt.util.annotation.ExcelImportLineIndex;
import vn.vnpt.util.common.JsonUtil;
import vn.vnpt.util.common.excel.model.ExcelFieldInfo;
import vn.vnpt.util.common.excel.model.ExcelFileInfo;
import vn.vnpt.util.common.excel.model.ExcelImportConfigInfo;
import vn.vnpt.util.exception.CustomException;

@Slf4j
@Component
public class ExcelReader {

  private static boolean isCellEmpty(Cell cell) {
    if (cell == null || cell.getCellType() == CellType.BLANK) {
      return true;
    }
    return cell.getCellType() == CellType.STRING && cell.getStringCellValue().isEmpty();
  }

  public <T> ExcelFileInfo<T> readFile(XSSFWorkbook wb, Class<T> aClass) {
    try {
      return this.readFileDataFromWB(wb, aClass);
    } catch (IOException e) {
      log.error("", e);
      throw new CustomException(e.getMessage());
    }
  }

  private <T> ExcelFileInfo<T> readFileDataFromWB(Workbook myWorkBook, Class<T> aClass)
      throws IOException {
    ExcelFileInfo<T> result = new ExcelFileInfo<>();

    ExcelImportConfigInfo config = this.getConfigInfo(aClass);
    List<Map<String, Object>> listMapData = new ArrayList<>();
    if (myWorkBook == null) {
      throw new IOException();
    }
    Sheet mySheet = myWorkBook.getSheetAt(config.getSheetIndex());
    Iterator<Row> rowIterator = mySheet.iterator();
    if (!rowIterator.hasNext()) {
      throw new IOException();
    }

    List<ExcelFieldInfo> listHeaderInfo = new ArrayList<>();
    while (rowIterator.hasNext()) {
      Row row = rowIterator.next();
      if (row == null) {
        break;
      } else if (row.getRowNum() < config.getHeaderRowIndex()) {
      } else if (row.getRowNum() == config.getHeaderRowIndex()) {
        Map<Integer, String> mapLabelInfo = this.getRowLabelInfo(row, config);
        listHeaderInfo.addAll(this.getHeaderInfo(mapLabelInfo, aClass, config));
        if (listHeaderInfo.isEmpty()) {
          throw new IOException();
        }
        Map<String, String> headerDataMap = new HashMap<>();
        for (ExcelFieldInfo excelFieldInfo : listHeaderInfo)
          headerDataMap.put(excelFieldInfo.getColName(), excelFieldInfo.getColLabel());
        result.setHeaderDataMap(headerDataMap);
      } else if (row.getRowNum() < config.getDataStartRowIndex()) {
      } else {
        if (config.getHeaderRowIndex() < 0) {
          // no header
          listHeaderInfo.addAll(this.getNoHeaderInfo(aClass, config));
        }
        Map<String, Object> rowData = this.getRowData(row, listHeaderInfo);
        if (!CollectionUtils.isEmpty(rowData)) {
          listMapData.add(rowData);
        }
      }
    }
    result.setRowDataList(JsonUtil.listMapToListObject(listMapData, aClass));
    return result;
  }

  private Map<Integer, String> getRowLabelInfo(Row row, ExcelImportConfigInfo config) {
    Map<Integer, String> mapLabelInfo = new HashMap<>();
    Iterator<Cell> cellIterator = row.cellIterator();
    boolean dataStart = false;
    while (cellIterator.hasNext()) {
      Cell cell = cellIterator.next();
      if (cell == null && dataStart) {
        break;
      }
      if (cell == null) {
        continue;
      }
      if (cell.getColumnIndex() < config.getStartColumnIndex() - 1) {
        continue;
      }
      // Skip cell trống trước khi gặp header đầu tiên có nội dung
      if (!dataStart) {
        if (cell.toString().isEmpty()) {
          continue;
        }
        dataStart = true;
      }
      if (cell.toString().isEmpty()) {
        break;
      }
      mapLabelInfo.put(cell.getColumnIndex(), cell.toString());
    }

    return mapLabelInfo;
  }

  private Map<String, Object> getRowData(Row row, List<ExcelFieldInfo> listHeaderInfo) {
    Map<String, Object> rowData = new HashMap<>();
    Cell cell;
    String cellData = null;
    boolean hasData = false;

    for (ExcelFieldInfo headerInfo : listHeaderInfo) {
      if (!headerInfo.isLineField()) {
        cell = row.getCell(headerInfo.getColIndex());
        if (cell != null && !isCellEmpty(cell)) {
          switch (cell.getCellType()) {
            case STRING:
              cellData = new DataFormatter().formatCellValue(cell).trim();
              break;
            case NUMERIC:
              BigDecimal number = new BigDecimal(cell.getNumericCellValue());
              if (number.stripTrailingZeros().scale() <= 0) {
                cellData = String.valueOf(number.intValue());
              } else {
                number = number.setScale(2, RoundingMode.HALF_EVEN);
                cellData = number.toPlainString();
              }
              break;
            case BOOLEAN:
              cellData = String.valueOf(cell.getBooleanCellValue()).trim();
              break;
            case FORMULA:
              switch (cell.getCachedFormulaResultType()) {
                case NUMERIC:
                  number = new BigDecimal(cell.getNumericCellValue());
                  if (number.stripTrailingZeros().scale() <= 0) {
                    cellData = String.valueOf(number.intValue());
                  } else {
                    number = number.setScale(2, RoundingMode.HALF_EVEN);
                    cellData = number.toPlainString();
                  }
                  break;
                case STRING:
                  cellData = new DataFormatter().formatCellValue(cell).trim();
                  break;
                default:
                  break;
              }
              break;
            default:
              break;
          }

          if (StringUtils.isBlank(cellData)) {
            cellData = null;
          } else {
            hasData = true;
          }
        } else {
          cellData = null;
        }

        rowData.put(headerInfo.getColName(), cellData);
      } else {
        rowData.put(headerInfo.getColName(), row.getRowNum() + 1);
      }
    }

    if (!hasData) {
      rowData = null;
    } else {
      rowData.put("rowNum", row.getRowNum());
    }

    return rowData;
  }

  private List<ExcelFieldInfo> getHeaderInfo(
      Map<Integer, String> mapLabelInfo, Class<?> aClass, ExcelImportConfigInfo config) {
    List<ExcelFieldInfo> listExcelHeaderModel = new ArrayList<>();
    Field[] fields = aClass.getDeclaredFields();
    for (Field field : fields) {
      if (field.isAnnotationPresent(ExcelImportLineIndex.class)) {
        ExcelFieldInfo header = new ExcelFieldInfo();
        header.setColIndex(0);
        header.setColName(field.getName());
        header.setColLabel(StringUtils.EMPTY);
        header.setLineField(true);
        listExcelHeaderModel.add(header);
      }
      if (!field.isAnnotationPresent(ExcelImport.class)) {
        continue;
      }
      for (Entry<Integer, String> entry : mapLabelInfo.entrySet()) {
        int headerIndex = entry.getKey();
        String headerValue = mapLabelInfo.get(headerIndex);
        ExcelImport fieldAnn = field.getAnnotation(ExcelImport.class);
        int columnIndex = fieldAnn.index() + config.getStartColumnIndex();
        if (headerIndex == columnIndex && headerIndex >= 0) {
          ExcelFieldInfo header = new ExcelFieldInfo();
          header.setColIndex(headerIndex);
          header.setColName(field.getName());
          header.setColLabel(headerValue);
          header.setLineField(false);
          listExcelHeaderModel.add(header);
          break;
        }
      }
    }

    return listExcelHeaderModel;
  }

  private List<ExcelFieldInfo> getNoHeaderInfo(Class<?> aClass, ExcelImportConfigInfo config) {
    List<ExcelFieldInfo> listExcelHeaderModel = new ArrayList<>();
    Field[] fields = aClass.getDeclaredFields();
    for (Field field : fields) {
      if (!field.isAnnotationPresent(ExcelImport.class)) {
        continue;
      }
      ExcelImport fieldAnn = field.getAnnotation(ExcelImport.class);
      int columnIndex = fieldAnn.index() + config.getStartColumnIndex();
      if (columnIndex >= 0) {
        ExcelFieldInfo cell = new ExcelFieldInfo();
        cell.setColIndex(columnIndex);
        cell.setColName(field.getName());
        cell.setLineField(false);
        listExcelHeaderModel.add(cell);
      }
    }

    return listExcelHeaderModel;
  }

  /**
   * get config
   *
   * @param entityClass dto clazz
   * @return ExcelImportConfigInfo
   */
  private ExcelImportConfigInfo getConfigInfo(Class<?> entityClass) {
    ExcelImportConfig config = entityClass.getAnnotation(ExcelImportConfig.class);
    ExcelImportConfigInfo info = new ExcelImportConfigInfo();
    info.setSheetIndex(config.sheetIndex());
    info.setStartColumnIndex(config.startColumnIndex());
    info.setHeaderRowIndex(config.headerRowIndex());
    info.setDataStartRowIndex(config.dataStartRowIndex());
    return info;
  }

  public ExcelFileInfo<Map<String, Object>> readFileWithNoClass(
      XSSFWorkbook wb, ExcelImportConfigInfo config) {
    try {
      return this.readDataFileWithNoClass(wb, config);
    } catch (IOException e) {
      log.error("", e);
      throw new CustomException(e.getMessage());
    }
  }

  public List<Map<String, Object>> readSheetAsMapsEvaluated(
      XSSFWorkbook wb, String sheetName, int startColIdx, int headerRowIdx, int dataStartRowIdx) {
    XSSFSheet sheet = wb.getSheet(sheetName);
    if (sheet == null) return Collections.emptyList();

    FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();
    DataFormatter formatter = new DataFormatter(Locale.getDefault());

    Row header = sheet.getRow(headerRowIdx);
    if (header == null) return Collections.emptyList();

    // Lấy danh sách header từ startColIdx tới lastCell
    int lastCell = header.getLastCellNum();
    List<String> headers = new ArrayList<>();
    for (int c = startColIdx; c < lastCell; c++) {
      Cell hc = header.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
      String key = hc == null ? null : formatter.formatCellValue(hc, evaluator);
      if (key == null || key.isBlank()) continue; // bỏ cột rỗng ở header
      headers.add(key.trim());
    }
    if (headers.isEmpty()) return Collections.emptyList();

    // Đọc từng dòng dữ liệu
    List<Map<String, Object>> rows = new ArrayList<>();
    for (int r = dataStartRowIdx; r <= sheet.getLastRowNum(); r++) {
      Row row = sheet.getRow(r);
      if (row == null) continue;
      Map<String, Object> map = new LinkedHashMap<>();
      boolean nonEmpty = false;

      for (int idx = 0; idx < headers.size(); idx++) {
        int c = startColIdx + idx;
        Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        String val = cell == null ? null : formatter.formatCellValue(cell, evaluator);
        if (val != null && !val.trim().isEmpty()) nonEmpty = true;
        map.put(headers.get(idx), val == null ? null : val.trim());
      }
      if (nonEmpty) rows.add(map);
    }
    return rows;
  }

  private ExcelFileInfo<Map<String, Object>> readDataFileWithNoClass(
      Workbook myWorkBook, ExcelImportConfigInfo config) throws IOException {
    ExcelFileInfo<Map<String, Object>> result = new ExcelFileInfo<>();
    Class<?> aClass = (Class<Map<String, Object>>) (Class) Map.class;
    List<Map<String, Object>> listMapData = new ArrayList<>();
    if (myWorkBook == null) {
      throw new IOException();
    }
    Sheet mySheet = myWorkBook.getSheetAt(config.getSheetIndex());
    Iterator<Row> rowIterator = mySheet.iterator();
    if (!rowIterator.hasNext()) {
      throw new IOException();
    }

    List<ExcelFieldInfo> listHeaderInfo = new ArrayList<>();
    while (rowIterator.hasNext()) {
      Row row = rowIterator.next();
      if (row == null) {
        break;
      } else if (row.getRowNum() < config.getHeaderRowIndex()) {
      } else if (row.getRowNum() == config.getHeaderRowIndex()) {
        Map<Integer, String> mapLabelInfo = this.getRowLabelInfo(row, config);
        listHeaderInfo.addAll(this.getHeaderInfoWithNoClass(mapLabelInfo, config));
        if (listHeaderInfo.isEmpty()) {
          throw new IOException();
        }
        Map<String, String> headerDataMap = new HashMap<>();
        for (ExcelFieldInfo excelFieldInfo : listHeaderInfo)
          headerDataMap.put(excelFieldInfo.getColName(), excelFieldInfo.getColLabel());
        result.setHeaderDataMap(headerDataMap);
      } else if (row.getRowNum() < config.getDataStartRowIndex()) {
      } else {
        if (config.getHeaderRowIndex() < 0) {
          // no header
          throw new IOException();
        }
        Map<String, Object> rowData = this.getRowData(row, listHeaderInfo);
        if (!CollectionUtils.isEmpty(rowData)) {
          listMapData.add(rowData);
        }
      }
    }
    result.setRowDataList(
        (List<Map<String, Object>>) JsonUtil.listMapToListObject(listMapData, aClass));
    return result;
  }

  private List<ExcelFieldInfo> getHeaderInfoWithNoClass(
      Map<Integer, String> mapLabelInfo, ExcelImportConfigInfo config) {
    List<ExcelFieldInfo> listExcelHeaderModel = new ArrayList<>();
    Set<Integer> keyList = mapLabelInfo.keySet();
    for (Integer key : keyList) {
      int headerIndex = key;
      String headerValue = mapLabelInfo.get(headerIndex);
      if (headerIndex >= 0 && key >= config.getStartColumnIndex()) {
        ExcelFieldInfo header = new ExcelFieldInfo();
        header.setColIndex(headerIndex);
        header.setColName(mapLabelInfo.get(key));
        header.setColLabel(headerValue);
        header.setLineField(false);
        listExcelHeaderModel.add(header);
      }
    }

    return listExcelHeaderModel;
  }
}
