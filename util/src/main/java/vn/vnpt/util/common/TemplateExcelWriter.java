package vn.vnpt.util.common;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.*;
import vn.vnpt.util.annotation.*;
import vn.vnpt.util.common.constant.CommonConstant;
import vn.vnpt.util.common.excel.ExcelData;
import vn.vnpt.util.common.excel.TitleInfo;
import vn.vnpt.util.exception.CustomException;

@Slf4j
public class TemplateExcelWriter {

  public <T> File setSingleDataToTemplate(T data) {
    return this.setSingleDataToTemplate(data, null, null, null, null);
  }

  public <T> File setSingleDataToTemplate(
      T data, String dynamicSheetName, String dynamicTitle, List<Integer> removeColumns) {
    return this.setSingleDataToTemplate(data, dynamicSheetName, dynamicTitle, removeColumns, null);
  }

  public <T> File setSingleDataToTemplate(
      T data,
      String dynamicSheetName,
      String dynamicTitle,
      List<Integer> columnsToRemove,
      List<Integer> columnsToHide) {
    return this.setSingleDataToTemplate(
        data, dynamicSheetName, dynamicTitle, columnsToRemove, columnsToHide, 1, 1);
  }

  public <T> File setSingleDataToTemplate(
      T data,
      String dynamicSheetName,
      String dynamicTitle,
      List<Integer> columnsToRemove,
      List<Integer> columnsToHide,
      Integer dynamicTitleRowIndex,
      Integer dynamicTitleCellIndex) {
    Class<?> classType = data.getClass();
    ExcelExportGeneralConfig config = classType.getAnnotation(ExcelExportGeneralConfig.class);
    if (config == null) {
      throw new CustomException("Lỗi config");
    }
    String templateName = getTemplateName(classType);
    File file = null;
    File templateFile = this.getTemplateFile(templateName);
    try (Workbook workbook = this.openWorkbook(templateFile)) {
      Sheet sheet = workbook.getSheetAt(0);
      if (dynamicSheetName != null && !dynamicSheetName.isEmpty()) {
        workbook.setSheetName(0, dynamicSheetName);
      }
      if (dynamicTitle != null && !dynamicTitle.isEmpty()) {
        Row titleRow = sheet.getRow(dynamicTitleRowIndex);
        if (titleRow == null) {
          titleRow = sheet.createRow(1);
        }
        Cell titleCell = titleRow.getCell(dynamicTitleCellIndex);
        if (titleCell == null) {
          titleCell = titleRow.createCell(1);
        }
        titleCell.setCellValue(dynamicTitle);
      }
      if (columnsToHide != null && !columnsToHide.isEmpty()) {
        for (int columnIndex : columnsToHide) {
          hideColumn(sheet, columnIndex);
        }
      }
      this.setSingleDataToSheet(data, sheet, config.hasSampleRow());
      if (columnsToRemove != null && !columnsToRemove.isEmpty()) {
        // Sắp xếp columnsToRemove giảm dần để xóa cột từ phải sang trái
        List<Integer> sortedColumns = new ArrayList<>(columnsToRemove);
        sortedColumns.sort(Comparator.reverseOrder());
        for (int columnIndex : sortedColumns) {
          removeColumn(sheet, columnIndex);
        }
      }
      file = this.writeToFile(templateFile, workbook, config);
    } catch (Exception e) {
      log.error("" + e);
      if (templateFile != null) {
        boolean deleteSuccess = templateFile.delete();
        log.debug("Delete file success: " + deleteSuccess);
      }
      throw new CustomException(e.getMessage());
    } finally {
      System.gc();
    }
    return file;
  }

  public <T> File setSingleDataToTemplateSheet(T data) {
    if (data == null) {
      return null;
    }

    Class<?> classType = data.getClass();
    final Field[] fields = classType.getDeclaredFields();
    ExcelExportGeneralConfig config = classType.getAnnotation(ExcelExportGeneralConfig.class);
    if (config == null) {
      throw new CustomException("Lỗi config");
    }

    String templateName = getTemplateName(classType);
    File file = null;
    File templateFile = this.getTemplateFile(templateName);
    ExcelExportForm fa;
    try (Workbook workbook = this.openWorkbook(templateFile)) {
      for (Field field : fields) {
        if (field.isAnnotationPresent(ExcelExportForm.class)) {
          // Lấy Annotation để biết sheetIndex
          fa = field.getAnnotation(ExcelExportForm.class);
          Sheet sheet = workbook.getSheetAt(fa.sheetIndex());

          int startRow = Objects.requireNonNull(fa).startRow();
          List<?> list = (List<?>) FieldUtils.readField(field, data, true);
          if (CollectionUtils.isNotEmpty(list)) {
            this.setDataInListToTemplate(
                list, startRow, sheet, config.hasSampleRow(), list.get(0).getClass());
          }
        }
      }

      file = this.writeToFile(templateFile, workbook, config);
    } catch (Exception e) {
      log.error("" + e);
      if (templateFile != null) {
        boolean deleteSuccess = templateFile.delete();
        log.debug("Delete file success: " + deleteSuccess);
      }
      throw new CustomException(e.getMessage());
    } finally {
      System.gc();
    }

    return file;
  }

  public void hideColumn(Sheet sheet, int columnIndex) {
    sheet.setColumnWidth(columnIndex, 0);
    sheet.setColumnHidden(columnIndex, true);
  }

  /**
   * Xóa cột tại vị trí columnIndex trong sheet. Các cột bên phải sẽ được dịch chuyển sang trái để
   * lấp đầy khoảng trống. Xử lý các ô gộp (merged cells) trước khi dịch chuyển.
   */
  public void removeColumn(Sheet sheet, int columnIndex) {
    int lastColumn = getLastColumnIndex(sheet);
    if (columnIndex < 0 || columnIndex > lastColumn) {
      log.warn(
          "Column index {} is out of bounds (max: {}). Skipping removal.", columnIndex, lastColumn);
      return;
    }

    // Xử lý các ô gộp trước khi dịch chuyển dữ liệu
    List<CellRangeAddress> mergedRegions = new ArrayList<>(sheet.getMergedRegions());
    for (CellRangeAddress region : mergedRegions) {
      int firstColumn = region.getFirstColumn();
      int lastColumnInRegion = region.getLastColumn();

      // Nếu cột bị xóa nằm trong phạm vi ô gộp
      if (columnIndex >= firstColumn && columnIndex <= lastColumnInRegion) {
        // Nếu ô gộp chỉ bao gồm cột bị xóa, bỏ gộp
        if (firstColumn == columnIndex && lastColumnInRegion == columnIndex) {
          sheet.removeMergedRegion(sheet.getMergedRegions().indexOf(region));
        }
        // Nếu cột bị xóa nằm trong phạm vi gộp, thu hẹp vùng gộp
        else {
          sheet.removeMergedRegion(sheet.getMergedRegions().indexOf(region));
          if (columnIndex == firstColumn) {
            // Xóa cột đầu tiên của vùng gộp, thu hẹp vùng gộp từ bên trái
            sheet.addMergedRegion(
                new CellRangeAddress(
                    region.getFirstRow(),
                    region.getLastRow(),
                    firstColumn + 1,
                    lastColumnInRegion));
          } else if (columnIndex == lastColumnInRegion) {
            // Xóa cột cuối cùng của vùng gộp, thu hẹp vùng gộp từ bên phải
            sheet.addMergedRegion(
                new CellRangeAddress(
                    region.getFirstRow(),
                    region.getLastRow(),
                    firstColumn,
                    lastColumnInRegion - 1));
          } else {
            //  thu hẹp vùng gộp
            sheet.addMergedRegion(
                new CellRangeAddress(
                    region.getFirstRow(),
                    region.getLastRow(),
                    firstColumn,
                    lastColumnInRegion - 1));
          }
        }
      }
      // Nếu cột bị xóa nằm bên trái vùng gộp, dịch chuyển vùng gộp sang trái
      else if (columnIndex < firstColumn) {
        sheet.removeMergedRegion(sheet.getMergedRegions().indexOf(region));
        sheet.addMergedRegion(
            new CellRangeAddress(
                region.getFirstRow(),
                region.getLastRow(),
                firstColumn - 1,
                lastColumnInRegion - 1));
      }
    }

    // Nếu cột bị xóa là cột ngoài cùng, xóa trực tiếp các ô trong cột
    if (columnIndex == lastColumn) {
      for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
        Row row = sheet.getRow(rowIndex);
        if (row == null) continue;
        Cell cell = row.getCell(columnIndex);
        if (cell != null) {
          row.removeCell(cell);
        }
      }
      return;
    }

    // Dịch chuyển dữ liệu sau khi xử lý ô gộp
    for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      if (row == null) continue;

      // Dịch chuyển từng cell từ columnIndex + 1 trở đi sang trái
      for (int col = columnIndex; col < lastColumn; col++) {
        Cell oldCell = row.getCell(col + 1);
        Cell newCell = row.getCell(col);

        if (newCell == null) {
          newCell = row.createCell(col);
        }

        if (oldCell != null) {
          // Xử lý kiểu dữ liệu của ô
          switch (oldCell.getCellType()) {
            case NUMERIC:
              newCell.setCellValue(oldCell.getNumericCellValue());
              break;
            case STRING:
              newCell.setCellValue(oldCell.getStringCellValue());
              break;
            case BOOLEAN:
              newCell.setCellValue(oldCell.getBooleanCellValue());
              break;
            case FORMULA:
              newCell.setCellFormula(oldCell.getCellFormula());
              break;
            case BLANK:
              newCell.setBlank();
              break;
            default:
              newCell.setCellValue(oldCell.toString());
              break;
          }
          newCell.setCellType(oldCell.getCellType());
          newCell.setCellStyle(oldCell.getCellStyle());
          row.removeCell(oldCell); // Xóa cell bên phải sau khi sao chép
        } else {
          row.removeCell(newCell); // Nếu không có cell bên phải, xóa cell hiện tại
        }
      }
    }
  }

  /** Tìm index của cột cuối cùng trong sheet. */
  private int getLastColumnIndex(Sheet sheet) {
    int maxColumn = 0;
    for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      if (row != null) {
        int lastCellNum = row.getLastCellNum();
        if (lastCellNum > maxColumn) {
          maxColumn = lastCellNum;
        }
      }
    }
    return maxColumn - 1; // Trả về index của cột cuối cùng
  }

  /**
   * Open workbook
   *
   * @return
   */
  public Workbook openWorkbook(File file) {
    Workbook wb = null;
    try (InputStream is = new FileInputStream(file)) {
      wb = new XSSFWorkbook(is);
    } catch (EncryptedDocumentException | IOException e) {
      log.error("Handle exception: ", e);
      throw new CustomException(e.getMessage());
    }
    return wb;
  }

  /**
   * Get template file by name
   *
   * @return
   */
  public static <T> String getTemplateName(Class<T> type) {
    ExcelExportGeneralConfig config = type.getAnnotation(ExcelExportGeneralConfig.class);
    if (config == null) {
      throw new CustomException("Annotation ExcelExportGeneralConfig is null");
    }
    return CommonConstant.EXCEL_TEMPLATE_FOLDER + config.template();
  }

  /**
   * Get template file by name
   *
   * @return
   */
  public File getTemplateFile(String fileName) {
    File tempFile = null;
    InputStream in = null;
    try {
      ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
      in = classLoader.getResourceAsStream(fileName);
      if (in == null) {
        throw new CustomException("Không tìm thấy tệp template: " + fileName);
      }
      tempFile = new File("/tmp/templates/excel/" + fileName.substring(16));

      // Tạo thư mục nếu chưa tồn tại
      File parentDir = tempFile.getParentFile();
      if (!parentDir.exists()) {
        parentDir.mkdirs();
      }
      if (!parentDir.canWrite()) {
        throw new CustomException("Không có quyền ghi vào: " + parentDir.getAbsolutePath());
      }
      Files.copy(in, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      log.error(
          "Lỗi sao chép tệp: "
              + e.getMessage()
              + ", đường dẫn: "
              + (tempFile != null ? tempFile.getAbsolutePath() : "null"));
      throw new CustomException("Không thể tạo tệp tạm: " + e.getMessage());
    } finally {
      IOUtils.closeQuietly(in);
    }
    return tempFile;
  }

  /** Write workbook to file and ouput response */
  public File writeToFile(File file, Workbook wb, ExcelExportGeneralConfig config) {
    try (FileOutputStream outputStream = new FileOutputStream(file)) {
      // Init all cell format with formula
      if (config.isEvaluateFormula()) {
        XSSFFormulaEvaluator.evaluateAllFormulaCells(wb);
      }
      wb.write(outputStream);
    } catch (IOException e) {
      log.error("" + e);
      throw new CustomException(e.getMessage());
    }
    return file;
  }

  protected <T> void setSingleDataToSheet(T data, Sheet sheet, boolean hasSamepleRow) {
    setSingleDataToSheet(data, sheet, hasSamepleRow, false);
  }

  protected <T> void setSingleDataToSheet(
      T data, Sheet sheet, boolean hasSamepleRow, boolean clearExistingListData) {
    if (data == null) {
      return;
    }

    Class<?> classType = data.getClass();
    final Field[] fields = classType.getDeclaredFields();
    ExcelExportForm fa;
    Object value;
    try {
      for (Field field : fields) {
        if (field.isAnnotationPresent(ExcelExportForm.class)) {
          fa = field.getAnnotation(ExcelExportForm.class);
          if (List.class.isAssignableFrom(field.getType())) {
            if (Objects.requireNonNull(fa).compareSheetName()
                && !fa.sheetName().equals(sheet.getSheetName())) {
              continue;
            }
            int startRow = Objects.requireNonNull(fa).startRow();
            List<?> list = (List<?>) FieldUtils.readField(field, data, true);
            if (CollectionUtils.isNotEmpty(list)) {
              this.setDataInListToTemplate(
                  list,
                  startRow,
                  sheet,
                  hasSamepleRow,
                  list.get(0).getClass(),
                  clearExistingListData);
            } else if (clearExistingListData) {
              Class<?> elementType = getListElementType(field);
              if (elementType != null) {
                this.setDataInListToTemplate(
                    list, startRow, sheet, hasSamepleRow, elementType, true);
              }
            }
          } else {
            String cellAddress = Objects.requireNonNull(fa).cell();
            String[] cellAddressArray = fa.cellArray();
            ValueDefinition def = field.getAnnotation(ValueDefinition.class);
            if (StringUtils.isNotBlank(cellAddress)) {
              Cell cell = this.getCellAtPosition(sheet, cellAddress);
              value = FieldUtils.readField(field, data, true);
              writeValueToCell(value, cell, def);
            } else {
              for (String address : cellAddressArray) {
                Cell cell = this.getCellAtPosition(sheet, address);
                value = FieldUtils.readField(field, data, true);
                this.writeValueToCell(value, cell, def);
              }
            }
          }
        }
      }
    } catch (IllegalArgumentException | IllegalAccessException e) {
      throw new CustomException(e.getMessage());
    }
  }

  public void writeValueToCell(Object value, Cell cell, ValueDefinition def) {
    if (value == null || value.toString().isEmpty()) {
      return;
    }
    this.setValueToCell(value, cell, def);
  }

  /**
   * @param value
   * @param cell
   * @param def
   */
  protected void setValueToCell(Object value, Cell cell, ValueDefinition def) {
    if (value instanceof Date) {
      cell.setCellValue((Date) value);
    }
    if (value instanceof Long
        || value instanceof Double
        || value instanceof BigDecimal
        || value instanceof Integer
        || value instanceof BigInteger) {
      BigDecimal tmp = new BigDecimal(value.toString());
      if (def != null) {
        try {
          tmp = tmp.setScale(def.decimalScale(), def.roundingMode());
        } catch (Exception e) {
          tmp = tmp.setScale(def.decimalScale(), RoundingMode.HALF_EVEN);
        }
      }
      cell.setCellValue(tmp.doubleValue());
    } else if (value instanceof String) {
      cell.setCellValue((String) value);
    } else if (value instanceof Boolean) {
      cell.setCellValue((Boolean) value);
    }
  }

  /**
   * Get cell at cell name address
   *
   * @param sheet
   * @param cellName
   * @return
   */
  public Cell getCellAtPosition(Sheet sheet, final String cellName) {
    CellReference cr = new CellReference(cellName);
    int rowIndex = cr.getRow();
    int colIndex = cr.getCol();

    Row row = sheet.getRow(rowIndex);
    if (row == null) {
      row = sheet.createRow(rowIndex);
    }

    Cell cell = row.getCell(colIndex);
    if (cell == null) {
      cell = row.createCell(colIndex);
    }
    return cell;
  }

  public void createCellandCopyStyle(Row templateRow, Row newRow) {
    if (null == templateRow) {
      return;
    }
    int lastCellNum = templateRow.getLastCellNum();
    for (int i = 0; i < lastCellNum; i++) {
      Cell cellTemplate = templateRow.getCell(i);
      if (cellTemplate != null) {
        getCellWithCreate(newRow, i).setCellStyle(cellTemplate.getCellStyle());
      }
    }
  }

  public Cell getCellWithCreate(Row row, int colIndex) {
    Cell cell = row.getCell(colIndex);
    if (cell == null) {
      cell = row.createCell(colIndex);
    }
    return cell;
  }

  public void getCellAndWriteValue(Object value, Row row, int columnIndex, ValueDefinition def) {
    if (value == null || value.toString().isEmpty()) {
      return;
    }

    Cell cell = row.getCell(columnIndex);
    if (cell == null) {
      cell = row.createCell(columnIndex);
    }
    this.setValueToCell(value, cell, def);
  }

  protected void setDataInListToTemplate(
      List<?> listDataInSheet, int startRow, Sheet sheet, boolean hasSampleRow, Class<?> type) {
    setDataInListToTemplate(listDataInSheet, startRow, sheet, hasSampleRow, type, false);
  }

  protected void setDataInListToTemplate(
      List<?> listDataInSheet,
      int startRow,
      Sheet sheet,
      boolean hasSampleRow,
      Class<?> type,
      boolean clearExistingData) {
    if (clearExistingData) {
      clearDataColumns(sheet, startRow, type);
    }
    if (CollectionUtils.isEmpty(listDataInSheet)) {
      return;
    }

    int rowSampleIndex = startRow;
    Row rowSample = sheet.getRow(rowSampleIndex);
    // If rowSample is null, create row
    if (rowSample == null) {
      rowSample = sheet.createRow(rowSampleIndex);
    }
    int rowIndex = rowSampleIndex;
    for (Object data : listDataInSheet) {
      if (rowIndex == rowSampleIndex) {
        this.setDataFromObject(data, rowSample, type);
        rowIndex++;
        continue;
      }

      Row row = sheet.getRow(rowIndex);
      if (row == null) {
        row = sheet.createRow(rowIndex);
      }

      if (hasSampleRow) {
        this.createCellandCopyStyle(rowSample, row);
      }

      this.setDataFromObject(data, row, type);
      rowIndex++;
    }
  }

  private void clearDataColumns(Sheet sheet, int startRow, Class<?> type) {
    if (sheet == null || type == null || startRow > sheet.getLastRowNum()) {
      return;
    }

    Set<Integer> columnIndexes = getExcelExportColumnIndexes(type);
    if (columnIndexes.isEmpty()) {
      return;
    }

    for (int rowIndex = startRow; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
      Row row = sheet.getRow(rowIndex);
      if (row == null) {
        continue;
      }
      for (Integer columnIndex : columnIndexes) {
        Cell cell = row.getCell(columnIndex);
        if (cell != null) {
          cell.setBlank();
        }
      }
    }
  }

  private Set<Integer> getExcelExportColumnIndexes(Class<?> type) {
    Set<Integer> columnIndexes = new HashSet<>();
    for (Field field : type.getDeclaredFields()) {
      if (!field.isAnnotationPresent(ExcelExport.class)) {
        continue;
      }

      ExcelExport annotation = field.getAnnotation(ExcelExport.class);
      if (annotation.indexArray().length > 0) {
        for (int index : annotation.indexArray()) {
          if (index >= 0) {
            columnIndexes.add(index);
          }
        }
      } else if (annotation.index() >= 0) {
        columnIndexes.add(annotation.index());
      }
    }
    return columnIndexes;
  }

  private Class<?> getListElementType(Field field) {
    Type genericType = field.getGenericType();
    if (!(genericType instanceof ParameterizedType parameterizedType)) {
      return null;
    }

    Type[] actualTypes = parameterizedType.getActualTypeArguments();
    if (actualTypes.length == 0 || !(actualTypes[0] instanceof Class<?> elementType)) {
      return null;
    }

    return elementType;
  }

  /**
   * Set data from fields of object
   *
   * @param data
   * @param row
   * @param type
   */
  public void setDataFromObject(Object data, Row row, Class<?> type) {
    final Field[] fields = type.getDeclaredFields();
    ExcelExport fa;
    for (Field field : fields) {
      if (field.isAnnotationPresent(ExcelExport.class)) {
        fa = field.getAnnotation(ExcelExport.class);
        int columnIndex = fa.index();
        int[] columnIndexArray = fa.indexArray();
        try {
          Object value = FieldUtils.readField(field, data, true);
          ValueDefinition def = field.getAnnotation(ValueDefinition.class);
          if (columnIndexArray.length > 0) {
            for (int index : columnIndexArray) {
              this.getCellAndWriteValue(value, row, index, def);
            }
          } else {
            this.getCellAndWriteValue(value, row, columnIndex, def);
          }
        } catch (IllegalArgumentException | IllegalAccessException e) {
          throw new CustomException(e.getMessage());
        }
      }
    }
  }

  public <T> int getColIndex(Class<T> type, String keyDto) throws NoSuchFieldException {
    return type.getDeclaredField(keyDto).getAnnotation(ExcelExport.class).index();
  }

  /**
   * Get start row index
   *
   * @return
   */
  public <T> int getStartRowIndex(Class<T> type) throws NoSuchFieldException {
    return type.getAnnotation(ExcelExportGeneralConfig.class).startRow();
  }

  public Row getRowWithCreate(Sheet sheet, int rowIndex) {
    Row row = sheet.getRow(rowIndex);
    if (row == null) {
      row = sheet.createRow(rowIndex);
    }
    return row;
  }

  public String getCellAddrress(Row row, int cellIndex) {
    Cell cell = row.getCell(cellIndex);
    if (cell == null) {
      cell = row.createCell(cellIndex);
    }
    return cell.getAddress().toString();
  }

  public void setDataToCell(Object value, Cell cell) {
    if (value instanceof Date) {
      cell.setCellValue((Date) value);
    }
    if (value instanceof Long
        || value instanceof Double
        || value instanceof BigDecimal
        || value instanceof Integer
        || value instanceof BigInteger
        || value instanceof Float) {
      BigDecimal tmp = new BigDecimal(value.toString());
      cell.setCellValue(tmp.doubleValue());
    } else if (value instanceof String) {
      cell.setCellValue((String) value);
    } else if (value instanceof Boolean) {
      cell.setCellValue((Boolean) value);
    }
  }

  public void setDataToCellCaterogy(Object value, Cell cell) {
    if (value instanceof Date) {
      cell.setCellValue((Date) value);
    }
    if (value instanceof Long
        || value instanceof Double
        || value instanceof BigDecimal
        || value instanceof Integer
        || value instanceof BigInteger) {
      cell.setCellValue(value.toString());
    } else if (value instanceof String) {
      cell.setCellValue((String) value);
    } else if (value instanceof Boolean) {
      cell.setCellValue((Boolean) value);
    }
  }

  public void setStyleForCell(
      Sheet sheetData, XSSFCellStyle cellStyle, Integer colIdx, Integer rowIdx) {
    Row rowTitle = this.getRowWithCreate(sheetData, rowIdx);
    Cell cellTitle = this.getCellWithCreate(rowTitle, colIdx);
    cellTitle.setCellStyle(cellStyle);
  }

  public String getRowNameByIndex(int rowNum, int number, Sheet sheet) {
    return sheet.getRow(rowNum).getCell(number).getAddress().toString();
  }

  public void mergeByRegion(int rowNum, int from, int to, Sheet sheet) {
    if (rowNum > 0 && from > 0 && to > 0 && to > from) {
      String startRange = getRowNameByIndex(rowNum, from, sheet);
      String endRange = getRowNameByIndex(rowNum, to, sheet);
      if (!startRange.isEmpty() && !endRange.isEmpty())
        sheet.addMergedRegion(CellRangeAddress.valueOf(startRange + ":" + endRange));
    }
  }

  public void mergeByRegionMultiRow(int rowNumFrom, int from, int rowNumTo, int to, Sheet sheet) {
    if (rowNumFrom > 0 && from > 0 && to > 0 && to > from && rowNumFrom != rowNumTo) {
      String startRange = getRowNameByIndex(rowNumFrom, from, sheet);
      String endRange = getRowNameByIndex(rowNumTo, to, sheet);
      if (!startRange.isEmpty() && !endRange.isEmpty())
        sheet.addMergedRegion(CellRangeAddress.valueOf(startRange + ":" + endRange));
    }
  }

  public void setXSSFValidation(XSSFSheet sheet, String formulas, Map<String, Object> params) {
    int firstRow = (int) params.get("firstRow");
    int lastRow = (int) params.get("lastRow");
    int firstCol = (int) params.get("firstCol");
    int lastCol = (int) params.get("lastCol");

    DataValidationHelper helper = sheet.getDataValidationHelper();
    CellRangeAddressList regions = new CellRangeAddressList(firstRow, lastRow, firstCol, lastCol);
    DataValidationConstraint constraint = helper.createFormulaListConstraint(formulas);
    DataValidation dataValidation = helper.createValidation(constraint, regions);

    if (dataValidation instanceof XSSFDataValidation) {
      dataValidation.setSuppressDropDownArrow(true);
      dataValidation.setShowErrorBox(true);
    } else {
      dataValidation.setSuppressDropDownArrow(false);
    }
    sheet.addValidationData(dataValidation);
  }

  public void setDataToCategorySheet(
      XSSFSheet sheet, List<Map<String, Object>> categories, List<String> keyList) {
    int dataRowStart = 0;
    for (Map<String, Object> category : categories) {
      AtomicInteger colStart = new AtomicInteger(0);
      Row row = getRowWithCreate(sheet, dataRowStart);
      keyList.forEach(
          k -> {
            Cell cell = getCellWithCreate(row, colStart.getAndIncrement());
            setDataToCell(category.getOrDefault(k, "").toString(), cell);
          });
      dataRowStart++;
    }
  }

  /**
   * @param params: the object param that contain keys "keyList", "pass", "firstRow", "lastRow",
   *     "firstCol", "lastCol"
   */
  public void createCategorySheet(
      XSSFWorkbook wb,
      XSSFSheet srcSheet,
      XSSFSheet desSheet,
      List<Map<String, Object>> categories,
      Map<String, Object> params) {
    if (categories.isEmpty()) return;
    @SuppressWarnings("unchecked")
    List<String> keyList = (List<String>) params.get("keyList");
    String pass = (String) params.get("pass");
    boolean isGenerateData = (boolean) params.getOrDefault("isGenerateData", true);

    if (isGenerateData) setDataToCategorySheet(srcSheet, categories, keyList);
    setXSSFValidation(desSheet, srcSheet.getSheetName() + "!$B$1:$B$" + categories.size(), params);
    wb.setSheetHidden(wb.getSheetIndex(srcSheet), true);
    srcSheet.protectSheet(pass);
  }

  public void createCategorySheetWithReference(
      XSSFWorkbook wb,
      XSSFSheet srcSheet,
      XSSFSheet desSheet,
      List<Map<String, Object>> categories,
      List<Map<String, Object>> refCategories,
      Map<String, Object> params) {
    @SuppressWarnings("unchecked")
    List<String> keyList = (List<String>) params.get("keyList");
    String pass = (String) params.get("pass");
    String refParentCode = (String) params.getOrDefault("refParentCode", "parentCode");
    String refCode = (String) params.getOrDefault("refCode", "code");
    Integer distanceToRef = (Integer) params.getOrDefault("distanceToRef", 1);
    String refSheetName = (String) params.get("refSheetName");
    String srcSheetName = srcSheet.getSheetName();
    boolean isGenerateData = (boolean) params.getOrDefault("isGenerateData", true);

    boolean isExtendRef = (boolean) params.getOrDefault("isExtendRef", false);
    Integer distanceRef = (Integer) params.getOrDefault("distanceRef", 2);
    Integer firstRow = (Integer) params.getOrDefault("firstRow", 5);
    String importSheetName = (String) params.getOrDefault("importSheetName", "import");

    if (isGenerateData) {
      AtomicInteger dataRowStart = new AtomicInteger(0);
      for (Map<String, Object> ref : refCategories) {
        List<Map<String, Object>> _categories =
            categories.stream()
                .filter(f -> f.get(refParentCode).toString().equals(ref.get(refCode).toString()))
                .collect(Collectors.toList());
        int initRowStart = dataRowStart.get() + 1;
        _categories.forEach(
            c -> {
              AtomicInteger colStart = new AtomicInteger(0);
              Row row = getRowWithCreate(srcSheet, dataRowStart.get());
              keyList.forEach(
                  k -> {
                    Cell cell = getCellWithCreate(row, colStart.getAndIncrement());
                    setDataToCell(c.getOrDefault(k, "").toString(), cell);
                  });
              dataRowStart.getAndIncrement();
            });

        XSSFName nameTmp = wb.createName();
        nameTmp.setNameName(
            srcSheetName + '_' + (ref.get("id") != null ? ref.get("id") : ref.get("code")));
        nameTmp.setRefersToFormula(
            srcSheetName + "!$B$" + initRowStart + ":$B$" + dataRowStart.get());
      }
    }

    String refFormulas = "";
    if (isExtendRef) {
      refFormulas +=
          "INDIRECT(\""
              + importSheetName
              + "!\"&ADDRESS(ROW()-"
              + firstRow
              + ",COLUMN()-"
              + distanceRef
              + "))";
    } else {
      refFormulas +=
          "INDEX("
              + refSheetName
              + "!$A$1:$A$"
              + refCategories.size()
              + ",MATCH(INDIRECT(ADDRESS(ROW(),COLUMN()-"
              + distanceToRef
              + ")),"
              + refSheetName
              + "!$B$1:$B$"
              + refCategories.size()
              + ",0))";
    }
    String formulas = "INDIRECT(\"" + srcSheetName + "_\"&" + refFormulas + ")";
    setXSSFValidation(desSheet, formulas, params);

    wb.setSheetHidden(wb.getSheetIndex(srcSheet), true);
    srcSheet.protectSheet(pass);
  }

  // Lấy danh sách xã, thôn theo parentCode vì lấy theo name sẽ bị trùng
  // dẫn đến danh sách thôn/
  public void createCategorySheetWithReferenceCode(
      XSSFWorkbook wb,
      XSSFSheet srcSheet,
      XSSFSheet desSheet,
      List<Map<String, Object>> categories,
      List<Map<String, Object>> refCategories,
      Map<String, Object> params) {
    @SuppressWarnings("unchecked")
    List<String> keyList = (List<String>) params.get("keyList");
    String pass = (String) params.get("pass");
    String refParentCode = (String) params.getOrDefault("refParentCode", "parentCode");
    String refCode = (String) params.getOrDefault("refCode", "code");
    Integer distanceToRef = (Integer) params.getOrDefault("distanceToRef", 1);
    String refSheetName = (String) params.get("refSheetName");
    String srcSheetName = srcSheet.getSheetName();
    boolean isGenerateData = (boolean) params.getOrDefault("isGenerateData", true);

    if (isGenerateData) {
      AtomicInteger dataRowStart = new AtomicInteger(0);
      for (Map<String, Object> ref : refCategories) {
        List<Map<String, Object>> _categories =
            categories.stream()
                .filter(f -> f.get(refParentCode).toString().equals(ref.get(refCode).toString()))
                .collect(Collectors.toList());
        int initRowStart = dataRowStart.get() + 1;
        _categories.forEach(
            c -> {
              AtomicInteger colStart = new AtomicInteger(0);
              Row row = getRowWithCreate(srcSheet, dataRowStart.get());
              keyList.forEach(
                  k -> {
                    Cell cell = getCellWithCreate(row, colStart.getAndIncrement());
                    setDataToCell(c.getOrDefault(k, "").toString(), cell);
                  });
              dataRowStart.getAndIncrement();
            });

        XSSFName nameTmp = wb.createName();
        nameTmp.setNameName(
            srcSheetName + '_' + (ref.get("id") != null ? ref.get("id") : ref.get("code")));
        nameTmp.setRefersToFormula(
            srcSheetName + "!$B$" + initRowStart + ":$B$" + dataRowStart.get());
      }
    }
    // cột hiện tại + 2 là cột code
    String refFormulas =
        "INDEX("
            + refSheetName
            + "!$A$1:$A$"
            + refCategories.size()
            + ",MATCH(INDIRECT(ADDRESS(ROW(),COLUMN()+"
            + 1
            + ")),"
            + refSheetName
            + "!$A$1:$A$"
            + refCategories.size()
            + ",0))";
    String formulas = "INDIRECT(\"" + srcSheetName + "_\"&" + refFormulas + ")";
    setXSSFValidation(desSheet, formulas, params);

    wb.setSheetHidden(wb.getSheetIndex(srcSheet), true);
    srcSheet.protectSheet(pass);
  }

  public <T> File setSingleDataToTemplateMergeRow(T data) {
    Class<?> classType = data.getClass();
    ExcelExportGeneralConfig config = classType.getAnnotation(ExcelExportGeneralConfig.class);
    if (config == null) {
      throw new CustomException("Lỗi config");
    }
    String templateName = this.getTemplateName(classType);
    File file = null;
    File templateFile = this.getTemplateFile(templateName);
    try (Workbook workbook = this.openWorkbook(templateFile)) {
      Sheet sheet = workbook.getSheetAt(0);
      this.setSingleDataToSheetMergeRow(data, sheet, config.hasSampleRow());
      if (config.dynamicTitle()) {
        setTitle(sheet, data);
      }
      file = this.writeToFile(templateFile, workbook, config);
    } catch (Exception e) {
      log.error("" + e);
      if (templateFile != null) {
        boolean deleteSuccess = templateFile.delete();
        log.debug("Delete file success: " + deleteSuccess);
      }
      throw new CustomException(e.getMessage());
    } finally {
      System.gc();
    }
    return file;
  }

  protected <T> void setSingleDataToSheetMergeRow(T data, Sheet sheet, boolean hasSamepleRow) {
    if (data == null) {
      return;
    }

    Class<?> classType = data.getClass();
    final Field[] fields = classType.getDeclaredFields();
    ExcelExportForm fa;
    Object value;
    try {
      for (Field field : fields) {
        if (field.isAnnotationPresent(ExcelExportForm.class)) {
          fa = field.getAnnotation(ExcelExportForm.class);
          if (List.class.isAssignableFrom(field.getType())) {
            int startRow = fa.startRow();
            List<?> list = (List<?>) FieldUtils.readField(field, data, true);
            if (CollectionUtils.isNotEmpty(list)) {
              this.setDataInListToTemplateMergeRow(
                  list, startRow, sheet, hasSamepleRow, list.get(0).getClass());
            }
          } else {
            String cellAddress = fa.cell();
            String[] cellAddressArray = fa.cellArray();
            ValueDefinition def = field.getAnnotation(ValueDefinition.class);
            if (StringUtils.isNotBlank(cellAddress)) {
              Cell cell = this.getCellAtPosition(sheet, cellAddress);
              value = FieldUtils.readField(field, data, true);
              this.writeValueToCell(value, cell, def);
            } else if (cellAddressArray.length > 0) {
              for (String address : cellAddressArray) {
                Cell cell = this.getCellAtPosition(sheet, address);
                value = FieldUtils.readField(field, data, true);
                this.writeValueToCell(value, cell, def);
              }
            }
          }
        }
      }
    } catch (IllegalArgumentException | IllegalAccessException e) {
      throw new CustomException(e.getMessage());
    }
  }

  protected void setDataInListToTemplateMergeRow(
      List<?> listDataInSheet, int startRow, Sheet sheet, boolean hasSampleRow, Class<?> type) {
    if (CollectionUtils.isEmpty(listDataInSheet)) {
      return;
    }
    int rowSampleIndex = startRow;
    Row rowSample = sheet.getRow(rowSampleIndex);
    // If rowSample is null, create row
    if (rowSample == null) {
      rowSample = sheet.createRow(rowSampleIndex);
    }
    int rowIndex = rowSampleIndex;
    ExcelData excelData = new ExcelData();
    long listDataSize = listDataInSheet.size();
    for (Object data : listDataInSheet) {
      excelData.setCurrentIndex(rowIndex);
      if ((rowIndex - rowSampleIndex + 1) < listDataSize) {
        excelData.setAfterData(listDataInSheet.get((rowIndex - rowSampleIndex + 1)));
      } else {
        excelData.setAfterData(null);
      }
      if (rowIndex == rowSampleIndex) {
        excelData.setCurrentData(data);
        excelData.setRow(rowSample);
        excelData.setType(type);
        this.setDataFromObjectMergeRow(excelData, sheet);
        rowIndex++;
        continue;
      }

      Row row = sheet.getRow(rowIndex);
      if (row == null) {
        row = sheet.createRow(rowIndex);
      }

      if (hasSampleRow) {
        this.createCellandCopyStyle(rowSample, row);
      }

      excelData.setCurrentData(data);
      excelData.setBeforeData(listDataInSheet.get(rowIndex - rowSampleIndex - 1));
      excelData.setRow(row);
      excelData.setType(type);
      this.setDataFromObjectMergeRow(excelData, sheet);
      rowIndex++;
    }
  }

  /**
   * Set data from fields of object
   *
   * @param excelData
   * @param sheet
   */
  protected void setDataFromObjectMergeRow(ExcelData excelData, Sheet sheet) {
    final Field[] fields = excelData.getType().getDeclaredFields();
    boolean isMergeRow = false;
    ExcelExport fa;
    List listCompareValue = new ArrayList();
    for (Field field : fields) {
      if (field.isAnnotationPresent(ExcelExport.class)) {
        fa = field.getAnnotation(ExcelExport.class);
        if (fa.isCompare()) {
          List list = new ArrayList<>();
          try {
            if (excelData.getBeforeData() != null) {
              list.add(FieldUtils.readField(field, excelData.getBeforeData(), true));
            }
            if (excelData.getCurrentData() != null) {
              list.add(FieldUtils.readField(field, excelData.getCurrentData(), true));
            }
            if (excelData.getAfterData() != null) {
              list.add(FieldUtils.readField(field, excelData.getAfterData(), true));
            }
            listCompareValue.add(list);
          } catch (IllegalArgumentException | IllegalAccessException e) {
            throw new CustomException(e.getMessage());
          }
        }
      }
    }
    if (listCompareValue.size() > 0) {
      if (excelData.getBeforeData() == null || !this.isSameWithBeforeData(listCompareValue)) {
        isMergeRow = false;
        excelData.setStartMergeIndex(excelData.getCurrentIndex());
      } else if (excelData.getAfterData() == null && excelData.getStartMergeIndex() != null) {
        isMergeRow = true;
        excelData.setEndMergeIndex(excelData.getCurrentIndex());
      } else if (!this.isSameWithAfterData(listCompareValue)) {
        isMergeRow = true;
        excelData.setEndMergeIndex(excelData.getCurrentIndex());
      }
    } else {
      isMergeRow = false;
    }

    for (Field field : fields) {
      if (field.isAnnotationPresent(ExcelExport.class)) {
        fa = field.getAnnotation(ExcelExport.class);
        int columnIndex = fa.index();
        int[] columnIndexArray = fa.indexArray();
        try {
          Object value = FieldUtils.readField(field, excelData.getCurrentData(), true);
          ValueDefinition def = field.getAnnotation(ValueDefinition.class);
          if (columnIndexArray.length > 0) {
            for (int index : columnIndexArray) {
              if (index > 0) {
                if (fa.isNotMergeCell()) {
                  this.getCellAndWriteValue(value, excelData.getRow(), index, def);
                } else {
                  if (isMergeRow) {
                    sheet.addMergedRegion(
                        new CellRangeAddress(
                            excelData.getStartMergeIndex(),
                            excelData.getEndMergeIndex(),
                            index,
                            index));
                  } else {
                    this.getCellAndWriteValue(value, excelData.getRow(), index, def);
                  }
                }
              }
            }
          } else {
            if (columnIndex > 0) {
              if (fa.isNotMergeCell()) {
                this.getCellAndWriteValue(value, excelData.getRow(), columnIndex, def);
              } else {
                if (isMergeRow) {
                  sheet.addMergedRegion(
                      new CellRangeAddress(
                          excelData.getStartMergeIndex(),
                          excelData.getEndMergeIndex(),
                          columnIndex,
                          columnIndex));
                } else {
                  this.getCellAndWriteValue(value, excelData.getRow(), columnIndex, def);
                }
              }
            }
          }
        } catch (IllegalArgumentException | IllegalAccessException e) {
          throw new CustomException(e.getMessage());
        }
      }
    }
  }

  public boolean isSame(List<List> listCheck) {
    if (listCheck == null || listCheck.size() == 0) {
      return true;
    }
    List data = listCheck.get(0);
    if (data.get(0).equals(data.get(1)) && data.get(0).equals(data.get(2))) {
      if (listCheck.size() == 1) {
        return true;
      }
      return isSame(listCheck.subList(1, listCheck.size()));
    } else {
      return false;
    }
  }

  public boolean isSameWithBeforeData(List<List> listCheck) {
    if (listCheck == null || listCheck.size() == 0) {
      return true;
    }
    List data = listCheck.get(0);
    if ((data.get(0) == null && data.get(1) == null) || data.get(0).equals(data.get(1))) {
      if (listCheck.size() == 1) {
        return true;
      }
      return isSameWithBeforeData(listCheck.subList(1, listCheck.size()));
    } else {
      return false;
    }
  }

  public boolean isSameWithAfterData(List<List> listCheck) {
    if (listCheck == null || listCheck.size() == 0) {
      return true;
    }
    List data = listCheck.get(0);
    if ((data.get(1) == null && data.get(2) == null) || data.get(1).equals(data.get(2))) {
      if (listCheck.size() == 1) {
        return true;
      }
      return isSameWithAfterData(listCheck.subList(1, listCheck.size()));
    } else {
      return false;
    }
  }

  public void setXSSFListValidation(
      XSSFSheet sheet, String formulaString, int firstRow, int lastRow, int firstCol, int lastCol) {
    DataValidationHelper helper = sheet.getDataValidationHelper();
    CellRangeAddressList regions = new CellRangeAddressList(firstRow, lastRow, firstCol, lastCol);
    DataValidationConstraint constraint = helper.createFormulaListConstraint(formulaString);
    DataValidation dataValidation = helper.createValidation(constraint, regions);

    if (dataValidation instanceof XSSFDataValidation) {
      dataValidation.setSuppressDropDownArrow(true);
      dataValidation.setShowErrorBox(true);
    } else {
      dataValidation.setSuppressDropDownArrow(false);
    }
    sheet.addValidationData(dataValidation);
  }

  public void setXSSFIntegerValidation(
      XSSFSheet sheet,
      int firstRow,
      int lastRow,
      int firstCol,
      int lastCol,
      Long fromValue,
      Long endValue) {
    DataValidationHelper helper = sheet.getDataValidationHelper();
    CellRangeAddressList regions = new CellRangeAddressList(firstRow, lastRow, firstCol, lastCol);
    DataValidationConstraint constraint =
        helper.createIntegerConstraint(
            DataValidationConstraint.OperatorType.BETWEEN,
            fromValue.toString(),
            endValue.toString());
    DataValidation dataValidation = helper.createValidation(constraint, regions);

    if (dataValidation instanceof XSSFDataValidation) {
      if (fromValue >= 0) {
        dataValidation.createErrorBox(
            "Thông báo lỗi", "Định dạng dữ liệu phải là số nguyên không âm!");
        dataValidation.setShowErrorBox(true);
      } else {
        dataValidation.createErrorBox("Thông báo lỗi", "Định dạng dữ liệu phải là số nguyên!");
        dataValidation.setShowErrorBox(true);
      }
    }
    sheet.addValidationData(dataValidation);
  }

  public void setXSSFDecimalValidation(
      XSSFSheet sheet,
      int firstRow,
      int lastRow,
      int firstCol,
      int lastCol,
      Double fromValue,
      Double endValue) {
    DataValidationHelper helper = sheet.getDataValidationHelper();
    CellRangeAddressList regions = new CellRangeAddressList(firstRow, lastRow, firstCol, lastCol);
    DataValidationConstraint constraint =
        helper.createDecimalConstraint(
            DataValidationConstraint.OperatorType.BETWEEN,
            fromValue.toString(),
            endValue.toString());
    DataValidation dataValidation = helper.createValidation(constraint, regions);

    if (dataValidation instanceof XSSFDataValidation) {
      if (fromValue >= 0) {
        dataValidation.createErrorBox(
            "Thông báo lỗi", "Định dạng dữ liệu phải là số thập phân không âm!");
        dataValidation.setShowErrorBox(true);
      } else {
        dataValidation.createErrorBox("Thông báo lỗi", "Định dạng dữ liệu phải là số thập phân!");
        dataValidation.setShowErrorBox(true);
      }
    }
    sheet.addValidationData(dataValidation);
  }

  public void createDropListColumn(
      XSSFWorkbook wb,
      XSSFSheet sheet,
      List<Map<String, Object>> categories,
      List<String> keyList,
      int row,
      int addRowNum,
      int col,
      XSSFSheet desSheet,
      String pass,
      String codeKey,
      String categoryCode,
      XSSFSheet tempSheet) {
    setDataToCategorySheet(sheet, categories, keyList, codeKey, categoryCode);
    StringBuilder formula =
        new StringBuilder("'")
            .append(sheet.getSheetName())
            .append("'!$B$1:$B$")
            .append(categories.size());
    setXSSFListValidation(desSheet, formula.toString(), row, row + addRowNum, col, col);
    if (col >= 0) {
      String colName = CellReference.convertNumToColString(col);
      for (int i = row; i <= (row + addRowNum); i++) {
        Row formulaRow = getRowWithCreate(tempSheet, i);
        Cell formulaCell = getCellWithCreate(formulaRow, col);
        StringBuilder f = new StringBuilder("IFERROR(VLOOKUP(");
        f.append("'")
            .append(desSheet.getSheetName())
            .append("'!")
            .append(colName)
            .append(i + 1)
            .append(",'")
            .append(sheet.getSheetName())
            .append("'!$B$1:$C$")
            .append(categories.size())
            .append(",")
            .append(2)
            .append(",")
            .append("FALSE),\"\")");
        formulaCell.setCellFormula(f.toString());
        XSSFFormulaEvaluator formulaEvaluator = wb.getCreationHelper().createFormulaEvaluator();
        formulaEvaluator.evaluateFormulaCell(formulaCell);
      }
    }
    wb.setSheetHidden(wb.getSheetIndex(sheet), true);
    sheet.protectSheet(pass);
  }

  public void setDataToCategorySheet(
      XSSFSheet sheet,
      List<Map<String, Object>> categories,
      List<String> keyList,
      String codeKey,
      String categoryCode) {
    if (!categories.isEmpty()) {
      int dataRowStart = 0;
      for (Map<String, Object> dm : categories) {
        AtomicInteger colStart = new AtomicInteger(0);
        Row row = getRowWithCreate(sheet, dataRowStart);
        keyList.forEach(
            k -> {
              Cell cell = getCellWithCreate(row, colStart.getAndIncrement());
              setDataToCell(dm.get(k), cell);
            });
        Cell cell = getCellWithCreate(row, colStart.getAndIncrement());
        setDataToCell(categoryCode + dm.get(codeKey), cell);
        dataRowStart++;
      }
    }
  }

  public void createDropListColumnWithRefColumn(
      XSSFWorkbook wb,
      XSSFSheet crCategorySheet,
      List<Map<String, Object>> crCategories,
      List<Map<String, Object>> rfCategories,
      List<String> keyList,
      XSSFSheet desSheet,
      XSSFSheet tempSheet,
      int crCategoryRow,
      int addRowNum,
      int crCategoryCol,
      int rfCategoryCol,
      String pass,
      String compareColofCRCategory,
      String compareColofRFCategory,
      String codeKeyofCRCategory,
      boolean blankCell,
      String crCategoryCode,
      String rfCategoryCode) {
    AtomicInteger startRow = new AtomicInteger(0);
    String crSheetName = crCategoryCode + "_" + crCategoryCol + "_" + rfCategoryCol + "_";
    String rfSheetName = "RF_" + crSheetName + "_";
    XSSFSheet rfSheet = wb.getSheet(rfSheetName);
    if (rfSheet == null) {
      rfSheet = wb.createSheet(rfSheetName);
      if (wb.getName(rfSheetName) == null) {
        XSSFName rfName = wb.createName();
        rfName.setNameName(rfSheetName);
        StringBuilder rfFormula = new StringBuilder("'" + rfSheetName + "'!$A$1:$A$1");
        rfName.setRefersToFormula(rfFormula.toString());
      }
      wb.setSheetHidden(wb.getSheetIndex(rfSheet), true);
      rfSheet.protectSheet(pass);
    } else {
      if (wb.getName(rfSheetName) == null) {
        XSSFName rfName = wb.createName();
        rfName.setNameName(rfSheetName);
        StringBuilder rfFormula = new StringBuilder("'" + rfSheetName + "'!$A$1:$A$1");
        rfName.setRefersToFormula(rfFormula.toString());
      }
    }
    if (!rfCategories.isEmpty() && !crCategories.isEmpty()) {
      rfCategories.forEach(
          f -> {
            List<Map<String, Object>> dm =
                crCategories.stream()
                    .filter(
                        k -> k.get(compareColofCRCategory).equals(f.get(compareColofRFCategory)))
                    .collect(Collectors.toList());
            if (dm.size() > 0) {
              int initRowStart = startRow.get() + 1;
              if (blankCell) {
                AtomicInteger startCol = new AtomicInteger(0);
                keyList.forEach(
                    k -> {
                      Row row = getRowWithCreate(crCategorySheet, startRow.get());
                      Cell cell = getCellWithCreate(row, startCol.getAndIncrement());
                      setDataToCell(null, cell);
                    });
                startRow.getAndIncrement();
              }
              dm.forEach(
                  (item) -> {
                    AtomicInteger startCol = new AtomicInteger(0);
                    Row row = getRowWithCreate(crCategorySheet, startRow.get());
                    keyList.forEach(
                        k -> {
                          Cell cell = getCellWithCreate(row, startCol.getAndIncrement());
                          setDataToCell(item.get(k), cell);
                        });
                    Cell cell = getCellWithCreate(row, startCol.getAndIncrement());
                    setDataToCell(crCategoryCode + item.get(codeKeyofCRCategory), cell);
                    startRow.getAndIncrement();
                  });
              XSSFName nameTmp = wb.createName();
              nameTmp.setNameName(crSheetName + rfCategoryCode + f.get(compareColofRFCategory));
              StringBuilder formula =
                  new StringBuilder("'")
                      .append(crCategorySheet.getSheetName())
                      .append("'!$B$")
                      .append(initRowStart)
                      .append(":$B$")
                      .append(startRow.get());
              nameTmp.setRefersToFormula(formula.toString());
              XSSFName nameTmp2 = wb.createName();
              nameTmp2.setNameName(rfSheetName + rfCategoryCode + f.get(compareColofRFCategory));
              StringBuilder formula2 =
                  new StringBuilder("'")
                      .append(crCategorySheet.getSheetName())
                      .append("'!$B$")
                      .append(initRowStart)
                      .append(":$")
                      .append(CellReference.convertNumToColString(keyList.size()))
                      .append("$")
                      .append(startRow.get());
              nameTmp2.setRefersToFormula(formula2.toString());
            }
          });
    }
    int coldiff = rfCategoryCol - crCategoryCol;
    StringBuilder validation =
        new StringBuilder("INDIRECT(\"")
            .append(crSheetName)
            .append("\"&SUBSTITUTE(OFFSET(INDIRECT(")
            .append("\"'")
            .append(tempSheet.getSheetName())
            .append("'!\"&")
            .append("ADDRESS(ROW(),COLUMN()");
    if (coldiff > 0) {
      validation.append("+");
      validation.append(coldiff);
    } else {
      validation.append(coldiff);
    }

    validation.append(")),0,0),\" \",\"_\"))");

    setXSSFListValidation(
        desSheet,
        validation.toString(),
        crCategoryRow,
        crCategoryRow + addRowNum,
        crCategoryCol,
        crCategoryCol);
    String colName = CellReference.convertNumToColString(crCategoryCol);
    for (int i = crCategoryRow; i <= (crCategoryRow + addRowNum); i++) {
      Row formulaRow = getRowWithCreate(tempSheet, i);
      Cell formulaCell = getCellWithCreate(formulaRow, crCategoryCol);
      StringBuilder f = new StringBuilder("IFERROR(VLOOKUP(");
      f.append("'")
          .append(desSheet.getSheetName())
          .append("'!")
          .append(colName)
          .append(i + 1)
          .append(",")
          .append("INDIRECT((\"")
          .append(rfSheetName)
          .append("\"&SUBSTITUTE(OFFSET(INDIRECT(")
          .append("\"'")
          .append(tempSheet.getSheetName())
          .append("'!\"&")
          .append("ADDRESS(ROW(),COLUMN()");
      if ((rfCategoryCol - crCategoryCol) > 0) {
        f.append("+");
        f.append(rfCategoryCol - crCategoryCol);
      } else {
        f.append(rfCategoryCol - crCategoryCol);
      }

      f.append(")),0,0),\" \",\"_\"))),").append(keyList.size()).append(",FALSE), \"\")");
      formulaCell.setCellFormula(f.toString());
      XSSFFormulaEvaluator formulaEvaluator = wb.getCreationHelper().createFormulaEvaluator();
      formulaEvaluator.evaluateFormulaCell(formulaCell);
    }
    wb.setSheetHidden(wb.getSheetIndex(crCategorySheet), true);
    crCategorySheet.protectSheet(pass);
  }

  public void createDropListColumnWithRefColumnHoNuoi(
      XSSFWorkbook wb,
      XSSFSheet crCategorySheet,
      List<Map<String, Object>> crCategories,
      List<Map<String, Object>> rfCategories,
      List<String> keyList,
      XSSFSheet desSheet,
      XSSFSheet tempSheet,
      int crCategoryRow,
      int addRowNum,
      int crCategoryCol,
      int rfCategoryCol,
      String pass,
      String compareColofCRCategory,
      String compareColofRFCategory,
      String codeKeyofCRCategory,
      boolean blankCell,
      String crCategoryCode,
      String rfCategoryCode) {
    AtomicInteger startRow = new AtomicInteger(0);
    String crSheetName = crCategoryCode + "_" + crCategoryCol + "_" + rfCategoryCol + "_";
    String rfSheetName = "RF_" + crSheetName + "_";

    if (!rfCategories.isEmpty() && !crCategories.isEmpty()) {
      rfCategories.forEach(
          f -> {
            List<Map<String, Object>> dm =
                crCategories.stream()
                    .filter(
                        k -> {
                          Object crValue = k.get(compareColofCRCategory);
                          Object rfValue = f.get(compareColofRFCategory);
                          return crValue != null
                              && rfValue != null
                              && crValue.toString().equals(rfValue.toString());
                        })
                    .collect(Collectors.toList());

            if (!dm.isEmpty()) {
              int initRowStart = startRow.get() + 1;
              if (blankCell) {
                getRowWithCreate(crCategorySheet, startRow.getAndIncrement());
              }

              dm.forEach(
                  (item) -> {
                    AtomicInteger startCol = new AtomicInteger(0);
                    Row row = getRowWithCreate(crCategorySheet, startRow.get());
                    keyList.forEach(
                        k -> {
                          Cell cell = getCellWithCreate(row, startCol.getAndIncrement());
                          setDataToCell(item.get(k), cell);
                        });
                    Cell cell = getCellWithCreate(row, startCol.getAndIncrement());
                    setDataToCell(crCategoryCode + item.get(codeKeyofCRCategory), cell);
                    startRow.getAndIncrement();
                  });

              String nameSuffix =
                  (f.get(compareColofRFCategory) != null)
                      ? f.get(compareColofRFCategory).toString()
                      : "";

              XSSFName nameTmp = wb.createName();
              nameTmp.setNameName(crSheetName + rfCategoryCode + nameSuffix);
              StringBuilder formula =
                  new StringBuilder("'")
                      .append(crCategorySheet.getSheetName())
                      .append("'!$B$")
                      .append(initRowStart)
                      .append(":$B$")
                      .append(startRow.get());
              nameTmp.setRefersToFormula(formula.toString());

              XSSFName nameTmp2 = wb.createName();
              nameTmp2.setNameName(rfSheetName + rfCategoryCode + nameSuffix);
              StringBuilder formula2 =
                  new StringBuilder("'")
                      .append(crCategorySheet.getSheetName())
                      .append("'!$B$")
                      .append(initRowStart)
                      .append(":$")
                      .append(CellReference.convertNumToColString(keyList.size()))
                      .append("$")
                      .append(startRow.get());
              nameTmp2.setRefersToFormula(formula2.toString());
            }
          });
    }

    String lookupSheetName = crSheetName + "lookup";
    XSSFSheet lookupSheet = wb.createSheet(lookupSheetName);
    AtomicInteger lookupRow = new AtomicInteger(0);
    for (Map<String, Object> rfItem : rfCategories) {
      Row row = getRowWithCreate(lookupSheet, lookupRow.getAndIncrement());
      getCellWithCreate(row, 0).setCellValue(rfItem.get("name").toString());
      getCellWithCreate(row, 1).setCellValue(rfItem.get(compareColofRFCategory).toString());
    }
    wb.setSheetHidden(wb.getSheetIndex(lookupSheet), true);

    int helperColIndex = 500;
    String helperColName = CellReference.convertNumToColString(helperColIndex);

    for (int i = crCategoryRow; i <= (crCategoryRow + addRowNum); i++) {
      Row formulaRow = getRowWithCreate(tempSheet, i);
      String parentCellAddress =
          "'"
              + desSheet.getSheetName()
              + "'!"
              + CellReference.convertNumToColString(rfCategoryCol)
              + (i + 1);

      String vlookupToGetIdFormula =
          "VLOOKUP(" + parentCellAddress + ",'" + lookupSheetName + "'!$A:$B,2,FALSE)";
      String helperFormula =
          "IFERROR(\"" + crSheetName + rfCategoryCode + "\" & " + vlookupToGetIdFormula + ", \"\")";
      Cell helperCell = getCellWithCreate(formulaRow, helperColIndex);
      helperCell.setCellFormula(helperFormula);

      String validationFormula =
          "INDIRECT(" + tempSheet.getSheetName() + "!" + helperColName + (i + 1) + ")";
      setXSSFListValidation(desSheet, validationFormula, i, i, crCategoryCol, crCategoryCol);

      String currentDropdownCellAddress =
          "'"
              + desSheet.getSheetName()
              + "'!"
              + CellReference.convertNumToColString(crCategoryCol)
              + (i + 1);
      String vlookupTableRangeNameFormula =
          "IFERROR(\"" + rfSheetName + rfCategoryCode + "\" & " + vlookupToGetIdFormula + ", \"\")";

      StringBuilder vlookupFormula = new StringBuilder("IFERROR(VLOOKUP(");
      vlookupFormula.append(currentDropdownCellAddress).append(",");
      vlookupFormula.append("INDIRECT(").append(vlookupTableRangeNameFormula).append("),");
      vlookupFormula.append(keyList.size()).append(",");
      vlookupFormula.append("FALSE), \"\")");

      Cell formulaCell = getCellWithCreate(formulaRow, crCategoryCol);
      formulaCell.setCellFormula(vlookupFormula.toString());
    }

    wb.setSheetHidden(wb.getSheetIndex(crCategorySheet), true);
    crCategorySheet.protectSheet(pass);
  }

  public void createDropListColumnWithRefSheet(
      XSSFWorkbook wb,
      XSSFSheet crCategorySheet,
      XSSFSheet rfCategorySheet,
      List<Map<String, Object>> crCategories,
      List<Map<String, Object>> rfCategories,
      List<String> keyList,
      XSSFSheet desSheet,
      XSSFSheet tempSheet,
      int crCategoryRow,
      int rfCategoryRow,
      int addRowNum,
      int crCategoryCol,
      int rfCategoryCol,
      String pass,
      String compareColofCRCategory,
      String compareColofRFCategory,
      String codeKeyofCRCategory,
      boolean blankCell,
      String crCategoryCode,
      String rfCategoryCode) {
    String crSheetName = crCategoryCode + "_" + crCategoryCol + "_" + rfCategoryCol + "_";
    String rfSheetName = "RF_" + crSheetName + "_";
    XSSFSheet rfSheet = wb.getSheet(rfSheetName);
    if (rfSheet == null) {
      rfSheet = wb.createSheet(rfSheetName);
      if (wb.getName(rfSheetName) == null) {
        XSSFName rfName = wb.createName();
        rfName.setNameName(rfSheetName);
        StringBuilder rfFormula = new StringBuilder("'" + rfSheetName + "'!$A$1:$A$1");
        rfName.setRefersToFormula(rfFormula.toString());
      }
      wb.setSheetHidden(wb.getSheetIndex(rfSheet), true);
      rfSheet.protectSheet(pass);
    } else {
      if (wb.getName(rfSheetName) == null) {
        XSSFName rfName = wb.createName();
        rfName.setNameName(rfSheetName);
        StringBuilder rfFormula = new StringBuilder("'" + rfSheetName + "'!$A$1:$A$1");
        rfName.setRefersToFormula(rfFormula.toString());
      }
    }
    AtomicInteger startRow = new AtomicInteger(0);
    if (rfCategories.size() > 0 && crCategories.size() > 0) {
      rfCategories.forEach(
          f -> {
            List<Map<String, Object>> dm =
                crCategories.stream()
                    .filter(
                        k -> k.get(compareColofCRCategory).equals(f.get(compareColofRFCategory)))
                    .collect(Collectors.toList());
            if (dm.size() > 0) {
              int initRowStart = startRow.get() + 1;
              if (blankCell) {
                AtomicInteger startCol = new AtomicInteger(0);
                keyList.forEach(
                    k -> {
                      Row row = getRowWithCreate(crCategorySheet, startRow.get());
                      Cell cell = getCellWithCreate(row, startCol.getAndIncrement());
                      setDataToCell(null, cell);
                    });
                startRow.getAndIncrement();
              }
              dm.forEach(
                  (item) -> {
                    AtomicInteger startCol = new AtomicInteger(0);
                    Row row = getRowWithCreate(crCategorySheet, startRow.get());
                    keyList.forEach(
                        k -> {
                          Cell cell = getCellWithCreate(row, startCol.getAndIncrement());
                          setDataToCell(item.get(k), cell);
                        });
                    Cell cell = getCellWithCreate(row, startCol.getAndIncrement());
                    setDataToCell(crCategoryCode + item.get(codeKeyofCRCategory), cell);
                    startRow.getAndIncrement();
                  });
              XSSFName nameTmp = wb.createName();
              nameTmp.setNameName(crSheetName + rfCategoryCode + f.get(compareColofRFCategory));
              StringBuilder formula =
                  new StringBuilder("'")
                      .append(crCategorySheet.getSheetName())
                      .append("'!$B$")
                      .append(initRowStart)
                      .append(":$B$")
                      .append(startRow.get());
              nameTmp.setRefersToFormula(formula.toString());
              XSSFName nameTmp2 = wb.createName();
              nameTmp2.setNameName(rfSheetName + rfCategoryCode + f.get(compareColofRFCategory));
              StringBuilder formula2 =
                  new StringBuilder("'")
                      .append(crCategorySheet.getSheetName())
                      .append("'!$B$")
                      .append(initRowStart)
                      .append(":$")
                      .append(CellReference.convertNumToColString(keyList.size()))
                      .append("$")
                      .append(startRow.get());
              nameTmp2.setRefersToFormula(formula2.toString());
            }
          });
    }
    StringBuilder validation =
        new StringBuilder("INDIRECT(\"")
            .append(crSheetName)
            .append("\"&SUBSTITUTE(OFFSET(INDIRECT(\"'");
    validation
        .append(rfCategorySheet.getSheetName())
        .append("'!\"&ADDRESS(LEFT(INDIRECT(\"'")
        .append(desSheet.getSheetName())
        .append("'!\"&ADDRESS(ROW(),COLUMN()-1)), FIND(\"-\",INDIRECT(\"'")
        .append(desSheet.getSheetName())
        .append("'!\"&ADDRESS(ROW(),COLUMN()-1)))-1)+")
        .append(rfCategoryRow)
        .append(",")
        .append(rfCategoryCol)
        .append(")),0,0),\" \",\"_\"))");
    setXSSFListValidation(
        desSheet,
        validation.toString(),
        crCategoryRow,
        crCategoryRow + addRowNum,
        crCategoryCol,
        crCategoryCol);

    String colName = CellReference.convertNumToColString(crCategoryCol);
    for (int i = crCategoryRow; i <= (crCategoryRow + addRowNum); i++) {
      Row formulaRow = getRowWithCreate(tempSheet, i);
      Cell formulaCell = getCellWithCreate(formulaRow, crCategoryCol);
      StringBuilder f = new StringBuilder("IFERROR(VLOOKUP('");
      f.append(desSheet.getSheetName())
          .append("'!")
          .append(colName)
          .append((i + 1))
          .append(",INDIRECT((\"")
          .append(rfSheetName)
          .append("\"&SUBSTITUTE(OFFSET(INDIRECT(\"'")
          .append(rfCategorySheet.getSheetName())
          .append("'!\"&ADDRESS(LEFT(INDIRECT(\"'")
          .append(desSheet.getSheetName())
          .append("'!\"&ADDRESS(ROW(),COLUMN()-1)), FIND(\"-\",INDIRECT(\"'")
          .append(desSheet.getSheetName())
          .append("'!\"&ADDRESS(ROW(),COLUMN()-1)))-1)+")
          .append(rfCategoryRow)
          .append(",")
          .append(rfCategoryCol)
          .append(")),0,0),\" \",\"_\"))),5,FALSE), \"\")");
      formulaCell.setCellFormula(f.toString());
      XSSFFormulaEvaluator formulaEvaluator = wb.getCreationHelper().createFormulaEvaluator();
      formulaEvaluator.evaluateFormulaCell(formulaCell);
    }
    wb.setSheetHidden(wb.getSheetIndex(crCategorySheet), true);
    crCategorySheet.protectSheet(pass);
  }

  public Boolean checkValueGreaterAndEqualZero(Double value) {
    return value.compareTo(0d) >= 0;
  }

  public <T> File writeDataToTemplateWithTitle(T data, TitleInfo title) {
    Class<?> classType = data.getClass();
    ExcelExportGeneralConfig config = classType.getAnnotation(ExcelExportGeneralConfig.class);
    if (config == null) {
      throw new CustomException("Lỗi config");
    }
    String templateName = this.getTemplateName(classType);
    File file = null;
    File templateFile = this.getTemplateFile(templateName);
    try (Workbook workbook = this.openWorkbook(templateFile)) {
      Sheet sheet = workbook.getSheetAt(0);
      Cell titleCell = sheet.getRow(title.getRow()).getCell(title.getCol());
      writeValueToCell(title.getTitle(), titleCell, null);
      this.setSingleDataToSheet(data, sheet, config.hasSampleRow());
      file = this.writeToFile(templateFile, workbook, config);
    } catch (Exception e) {
      log.error("" + e);
      if (templateFile != null) {
        boolean deleteSuccess = templateFile.delete();
        log.debug("Delete file success: " + deleteSuccess);
      }
      throw new CustomException(e.getMessage());
    } finally {
      System.gc();
    }
    return file;
  }

  public <T> File setDataToMultilayerMerge(T data) {
    Class<?> classType = data.getClass();
    ExcelExportGeneralConfig config = classType.getAnnotation(ExcelExportGeneralConfig.class);
    if (config == null) {
      throw new CustomException("Lỗi config");
    }
    String templateName = this.getTemplateName(classType);
    File file = null;
    File templateFile = this.getTemplateFile(templateName);
    try (Workbook workbook = this.openWorkbook(templateFile)) {
      Sheet sheet = workbook.getSheetAt(0);
      this.setDataToSheetMultilayerMerge(data, sheet, config.hasSampleRow());
      file = this.writeToFile(templateFile, workbook, config);
    } catch (Exception e) {
      log.error("" + e);
      if (templateFile != null) {
        boolean deleteSuccess = templateFile.delete();
        log.debug("Delete file success: " + deleteSuccess);
      }
      throw new CustomException(e.getMessage());
    } finally {
      System.gc();
    }
    return file;
  }

  protected <T> void setDataToSheetMultilayerMerge(T data, Sheet sheet, boolean hasSamepleRow) {
    if (data == null) {
      return;
    }

    Class<?> classType = data.getClass();
    final Field[] fields = classType.getDeclaredFields();
    ExcelExportForm fa;
    Object value;
    try {
      for (Field field : fields) {
        if (field.isAnnotationPresent(ExcelExportForm.class)) {
          fa = field.getAnnotation(ExcelExportForm.class);
          if (List.class.isAssignableFrom(field.getType())) {
            int startRow = fa.startRow();
            List<?> list = (List<?>) FieldUtils.readField(field, data, true);
            if (CollectionUtils.isNotEmpty(list)) {
              this.setDataInListMultilayerMerge(
                  list, startRow, sheet, hasSamepleRow, list.get(0).getClass());
            }
          } else {
            String cellAddress = fa.cell();
            String[] cellAddressArray = fa.cellArray();
            ValueDefinition def = field.getAnnotation(ValueDefinition.class);
            if (StringUtils.isNotBlank(cellAddress)) {
              Cell cell = this.getCellAtPosition(sheet, cellAddress);
              value = FieldUtils.readField(field, data, true);
              this.writeValueToCell(value, cell, def);
            } else if (cellAddressArray.length > 0) {
              for (String address : cellAddressArray) {
                Cell cell = this.getCellAtPosition(sheet, address);
                value = FieldUtils.readField(field, data, true);
                this.writeValueToCell(value, cell, def);
              }
            }
          }
        }
      }
    } catch (IllegalArgumentException | IllegalAccessException e) {
      throw new CustomException(e.getMessage());
    }
  }

  protected void setDataInListMultilayerMerge(
      List<?> listDataInSheet, int startRow, Sheet sheet, boolean hasSampleRow, Class<?> type) {
    if (CollectionUtils.isEmpty(listDataInSheet)) {
      return;
    }
    int rowSampleIndex = startRow;
    Row rowSample = sheet.getRow(rowSampleIndex);
    // If rowSample is null, create row
    if (rowSample == null) {
      rowSample = sheet.createRow(rowSampleIndex);
    }
    int rowIndex = rowSampleIndex;
    Map<String, ExcelData> excelDataMap = new HashMap<>();
    final Field[] fields = type.getDeclaredFields();
    for (Field field : fields) {
      excelDataMap.put(field.getName(), new ExcelData());
    }
    long listDataSize = listDataInSheet.size();
    for (Object data : listDataInSheet) {
      Row row = sheet.getRow(rowIndex);
      if (row == null) {
        row = sheet.createRow(rowIndex);
      }
      for (Map.Entry<String, ExcelData> entry : excelDataMap.entrySet()) {
        ExcelData excelData = entry.getValue();
        excelData.setCurrentIndex(rowIndex);
        if ((rowIndex - rowSampleIndex + 1) < listDataSize) {
          excelData.setAfterData(listDataInSheet.get((rowIndex - rowSampleIndex + 1)));
        } else {
          excelData.setAfterData(null);
        }
        if (rowIndex == rowSampleIndex) {
          excelData.setCurrentData(data);
          excelData.setRow(rowSample);
          excelData.setType(type);
          continue;
        }
        excelData.setCurrentData(data);
        excelData.setBeforeData(listDataInSheet.get(rowIndex - rowSampleIndex - 1));
        excelData.setRow(row);
        excelData.setType(type);
      }
      if (rowIndex == rowSampleIndex) {
        this.setDataMultilayerMerge(excelDataMap, sheet, type);
        rowIndex++;
        continue;
      }

      if (hasSampleRow) {
        this.createCellandCopyStyle(rowSample, row);
      }
      this.setDataMultilayerMerge(excelDataMap, sheet, type);
      rowIndex++;
    }
  }

  /**
   * Set data from fields of object
   *
   * @param excelDataMap
   * @param sheet
   * @param type
   */
  protected void setDataMultilayerMerge(
      Map<String, ExcelData> excelDataMap, Sheet sheet, Class<?> type) {
    final Field[] fields = type.getDeclaredFields();
    for (Field field : fields) {
      ExcelData excelData = excelDataMap.get(field.getName());
      boolean isMergeRow = false;
      ExcelExport fa;
      List listCompareValue = new ArrayList();
      for (Field f : fields) {
        if (f.isAnnotationPresent(ExcelExport.class)) {
          fa = f.getAnnotation(ExcelExport.class);
          if (fa.isCompare()) {
            List list = new ArrayList<>();
            try {
              if (excelData.getBeforeData() != null) {
                list.add(FieldUtils.readField(f, excelData.getBeforeData(), true));
              }
              if (excelData.getCurrentData() != null) {
                list.add(FieldUtils.readField(f, excelData.getCurrentData(), true));
              }
              if (excelData.getAfterData() != null) {
                list.add(FieldUtils.readField(f, excelData.getAfterData(), true));
              }
              listCompareValue.add(list);
            } catch (IllegalArgumentException | IllegalAccessException e) {
              throw new CustomException(e.getMessage());
            }
          }
          if (f.getName().equals(field.getName())) {
            break;
          }
        }
      }
      if (listCompareValue.size() > 0) {
        if (excelData.getBeforeData() == null || !this.isSameWithBeforeData(listCompareValue)) {
          isMergeRow = false;
          excelData.setStartMergeIndex(excelData.getCurrentIndex());
        } else if (excelData.getAfterData() == null && excelData.getStartMergeIndex() != null) {
          isMergeRow = true;
          excelData.setEndMergeIndex(excelData.getCurrentIndex());
        } else if (!this.isSameWithAfterData(listCompareValue)) {
          isMergeRow = true;
          excelData.setEndMergeIndex(excelData.getCurrentIndex());
        }
      } else {
        isMergeRow = false;
      }

      if (field.isAnnotationPresent(ExcelExport.class)) {
        fa = field.getAnnotation(ExcelExport.class);
        int columnIndex = fa.index();
        int[] columnIndexArray = fa.indexArray();
        try {
          Object value = FieldUtils.readField(field, excelData.getCurrentData(), true);
          ValueDefinition def = field.getAnnotation(ValueDefinition.class);
          if (columnIndexArray.length > 0) {
            for (int index : columnIndexArray) {
              if (index > 0) {
                if (fa.isNotMergeCell()) {
                  this.getCellAndWriteValue(value, excelData.getRow(), index, def);
                } else {
                  if (isMergeRow) {
                    sheet.addMergedRegion(
                        new CellRangeAddress(
                            excelData.getStartMergeIndex(),
                            excelData.getEndMergeIndex(),
                            index,
                            index));
                  } else {
                    this.getCellAndWriteValue(value, excelData.getRow(), index, def);
                  }
                }
              }
            }
          } else {
            if (columnIndex >= 0) {
              if (fa.isNotMergeCell()) {
                this.getCellAndWriteValue(value, excelData.getRow(), columnIndex, def);
              } else {
                if (isMergeRow) {
                  sheet.addMergedRegion(
                      new CellRangeAddress(
                          excelData.getStartMergeIndex(),
                          excelData.getEndMergeIndex(),
                          columnIndex,
                          columnIndex));
                } else {
                  this.getCellAndWriteValue(value, excelData.getRow(), columnIndex, def);
                }
              }
            }
          }
        } catch (IllegalArgumentException | IllegalAccessException e) {
          throw new CustomException(e.getMessage());
        }
      }
    }
  }

  protected <T> void setTitle(Sheet sheet, T data) throws IllegalAccessException {
    if (data == null) {
      return;
    }

    Class<?> classType = data.getClass();
    final Field[] fields = classType.getDeclaredFields();
    ExcelExportForm fa;
    Object value;
    for (Field field : fields) {
      if (field.isAnnotationPresent(ExcelTitle.class)) {
        ExcelTitle excelTitle = field.getAnnotation(ExcelTitle.class);

        int beginCol = excelTitle.beginCol();
        int endCol = excelTitle.endCol();
        int rowNumber = excelTitle.row();

        value = FieldUtils.readField(field, data, true);

        // Get or create the row
        Row row = sheet.getRow(rowNumber);
        if (row == null) {
          row = sheet.createRow(rowNumber);
        }

        // Create the cell and set the title
        Cell cell = row.createCell(beginCol);
        cell.setCellValue(value.toString());

        // Merge cells
        if (beginCol != endCol) {
          sheet.addMergedRegion(new CellRangeAddress(rowNumber, rowNumber, beginCol, endCol));
        }

        CellStyle style = sheet.getWorkbook().getCellStyleAt(rowNumber);
        Font font = sheet.getWorkbook().createFont();
        font.setFontName("Times New Roman"); // Thiết lập font là Times New Roman
        font.setBold(true);
        font.setFontHeightInPoints((short) 18); // Thiết lập kích thước font
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER); // Căn giữa

        // Áp dụng kiểu cho ô
        cell.setCellStyle(style);
        return;
      }
    }
  }

  public Map<String, Object> validateRowInPattern(
      Row row,
      FormulaEvaluator formulaEvaluator,
      Map<String, Map<Integer, String>> objCheck,
      List<Integer> requires,
      List<Integer> indices) {
    Map<String, Object> result = new HashMap<>();

    List<String> error = new ArrayList<>();
    result.put("isEnd", false); // default the current row is not the last row

    Map<String, Pattern> patterns = new HashMap<>();
    patterns.put("positiveNumber", Pattern.compile("\\d+"));
    patterns.put("decimalNumber", Pattern.compile("\\d+(\\.\\d+)?"));
    patterns.put(
        "fullDate",
        Pattern.compile(
            "(0[1-9]|[12][0-9]|3[01]|[1-9])/(0[1-9]|1[012]|[1-9])/(1[0-9]{3}|[2-9][0-9]{3})"));
    patterns.put("monthYear", Pattern.compile("(0[1-9]|[1-2][0-9]|[1-9])/[1-2][0-9]{3}"));
    patterns.put("email", Pattern.compile("\\w+([.-]?\\w+)*@\\w+([.-]?\\w+)*(\\.\\w{2,3})+"));
    patterns.put("identityCard", Pattern.compile("([0-9]{9}|[0-9]{12})"));
    patterns.put(
        "phone",
        Pattern.compile(
            "(0|84)(\\s|\\.)?((2[0-9][0-9])|(3[2-9])|(5[689])|(7[06-9])|(8[01-689])|(9[0-46-9]))(\\d)(\\s|\\.)?(\\d{3})(\\s|\\.)?(\\d{3})"));
    patterns.put("ref", Pattern.compile("[1-9]+[0-9]*-.+"));
    patterns.put("latitude", Pattern.compile("^[-+]?([1-8]?[0-9](\\.\\d+)?|90(\\.0+)?)$"));
    patterns.put(
        "longitude", Pattern.compile("^[-+]?((1[0-7][0-9]|[1-9]?[0-9])(\\.\\d+)?|180(\\.0+)?)$"));

    AtomicBoolean isError = new AtomicBoolean(false);
    AtomicInteger countRequiredErr = new AtomicInteger();

    try {
      // Iterate through the list of indices
      for (int index : indices) {
        Cell cell = row.getCell(index);
        String cellValue =
            Optional.ofNullable(getNormalizedCellValue(cell, formulaEvaluator)).orElse("").trim();

        // Check if required index and value is empty
        if (requires.contains(index) && cellValue.isEmpty()) {
          isError.set(true);
          countRequiredErr.getAndIncrement();
        } else {
          if (cellValue.isEmpty()) continue;
          for (Map.Entry<String, Pattern> entry : patterns.entrySet()) {
            String type = entry.getKey();
            Pattern pattern = entry.getValue();
            Map<Integer, String> validationMap =
                objCheck.getOrDefault(type, Collections.emptyMap());

            if (validationMap.containsKey(index) && !pattern.matcher(cellValue).matches()) {
              isError.set(true);
              error.add(validationMap.get(index));
            }
          }
        }
      }
      ;

      // Check if all required fields are empty
      if (Objects.equals(countRequiredErr.get(), requires.size())) {
        result.put("isEnd", true);
      }
    } catch (Exception e) {
      result.put("isErr", true);
      result.put("content", "unknown");
      return result;
    }

    if (countRequiredErr.get() > 0) {
      error.add(0, CommonConstant.MISSING_REQUIRE_INFO);
    }

    result.put("isErr", isError.get());
    result.put("content", error);
    return result;
  }

  public String getNormalizedCellValue(Cell cell, FormulaEvaluator evaluator) {
    if (cell == null) return "";

    CellType type = cell.getCellType();
    if (type == CellType.FORMULA) {
      CellValue evaluated = evaluator.evaluate(cell);
      if (evaluated == null) return "";

      switch (evaluated.getCellType()) {
        case NUMERIC:
          BigDecimal bd = BigDecimal.valueOf(evaluated.getNumberValue());
          if (bd.stripTrailingZeros().scale() <= 0) {
            return bd.toBigInteger().toString();
          } else {
            return bd.toPlainString();
          }
        case STRING:
          return StringUtils.isBlank(evaluated.getStringValue())
              ? null
              : evaluated.getStringValue();
        case BOOLEAN:
          return String.valueOf(evaluated.getBooleanValue());
        case ERROR:
        default:
          return "";
      }
    } else if (type == CellType.NUMERIC) {
      BigDecimal bd = BigDecimal.valueOf(cell.getNumericCellValue());
      if (bd.stripTrailingZeros().scale() <= 0) {
        return bd.toBigInteger().toString();
      } else {
        return bd.toPlainString();
      }
    } else if (type == CellType.STRING) {
      return StringUtils.isBlank(cell.getStringCellValue()) ? null : cell.getStringCellValue();
    } else if (type == CellType.BOOLEAN) {
      return String.valueOf(cell.getBooleanCellValue());
    }

    return "";
  }

  private Map<String, Pattern> patternDef() {
    Map<String, Pattern> patterns = new HashMap<>();
    patterns.put("positiveNumber", Pattern.compile("\\d+"));
    patterns.put("decimalNumber", Pattern.compile("\\d+(\\.\\d+)?"));
    patterns.put(
        "fullDate",
        Pattern.compile(
            "(0[1-9]|[12][0-9]|3[01]|[1-9])/(0[1-9]|1[012]|[1-9])/(1[0-9]{3}|[2-9][0-9]{3})"));
    patterns.put("monthYear", Pattern.compile("(0[1-9]|[1-2][0-9]|[1-9])/[1-2][0-9]{3}"));
    patterns.put("email", Pattern.compile("\\w+([.-]?\\w+)*@\\w+([.-]?\\w+)*(\\.\\w{2,3})+"));
    patterns.put("identityCard", Pattern.compile("([0-9]{9}|[0-9]{12})"));
    patterns.put(
        "phone",
        Pattern.compile(
            "(0|84)(\\s|\\.)?((2[0-9][0-9])|(3[2-9])|(5[689])|(7[06-9])|(8[01-689])|(9[0-46-9]))(\\d)(\\s|\\.)?(\\d{3})(\\s|\\.)?(\\d{3})"));
    patterns.put("ref", Pattern.compile("[1-9]+[0-9]*-.+"));
    patterns.put("latitude", Pattern.compile("^[-+]?([1-8]?[0-9](\\.\\d+)?|90(\\.0+)?)$"));
    patterns.put(
        "longitude", Pattern.compile("^[-+]?((1[0-7][0-9]|[1-9]?[0-9])(\\.\\d+)?|180(\\.0+)?)$"));

    return patterns;
  }

  public Map<String, Object> validateRowInPattern(
      Row row,
      FormulaEvaluator formulaEvaluator,
      Map<String, Map<Integer, String>> objCheck,
      List<Integer> requires,
      List<Integer> indices,
      Map<Integer, Integer> lengthValidator) {
    Map<String, Object> result = new HashMap<>();

    List<String> error = new ArrayList<>();
    result.put("isEnd", false); // default the current row is not the last row

    // Regex patterns
    Map<String, Pattern> patterns = patternDef();

    AtomicBoolean isError = new AtomicBoolean(false);
    AtomicInteger countRequiredErr = new AtomicInteger();

    try {
      // Iterate through the list of indices
      for (int index : indices) {
        Cell cell = row.getCell(index);
        String cellValue =
            Optional.ofNullable(getNormalizedCellValue(cell, formulaEvaluator)).orElse("").trim();

        // Check if required index and value is empty
        if (requires.contains(index) && cellValue.isEmpty()) {
          isError.set(true);
          countRequiredErr.getAndIncrement();
        } else {
          if (cellValue.isEmpty()) continue;
          for (Map.Entry<String, Pattern> entry : patterns.entrySet()) {
            String type = entry.getKey();
            Pattern pattern = entry.getValue();
            Map<Integer, String> validationMap =
                objCheck.getOrDefault(type, Collections.emptyMap());

            if (validationMap.containsKey(index)) {
              boolean isInValid = !pattern.matcher(cellValue).matches();
              if (isInValid) {
                isError.set(true);
                error.add(validationMap.get(index));
              }
            }
          }

          if (!lengthValidator.isEmpty()) {
            Map<Integer, String> validationMap =
                objCheck.getOrDefault("length", Collections.emptyMap());
            if (validationMap.containsKey(index)) {
              boolean isInValid = lengthValidator.getOrDefault(index, 255) < cellValue.length();
              ;
              if (isInValid) {
                isError.set(true);
                error.add(validationMap.get(index));
              }
            }
          }
        }
      }
      ;

      // Check if all required fields are empty
      if (Objects.equals(countRequiredErr.get(), requires.size())) {
        result.put("isEnd", true);
      }
    } catch (Exception e) {
      result.put("isErr", true);
      result.put("content", "unknown");
      return result;
    }

    if (countRequiredErr.get() > 0) {
      error.add(0, CommonConstant.MISSING_REQUIRE_INFO);
    }

    result.put("isErr", isError.get());
    result.put("content", error);
    return result;
  }

  public void createCategorySheetWithReferenceVerOther(
      XSSFWorkbook wb,
      XSSFSheet srcSheet,
      XSSFSheet desSheet,
      List<Map<String, Object>> categories,
      List<Map<String, Object>> refCategories,
      Map<String, Object> params) {
    @SuppressWarnings("unchecked")
    List<String> keyList = (List<String>) params.get("keyList");
    String pass = (String) params.get("pass");
    String refSheetName = (String) params.get("refSheetName");
    String srcSheetName = srcSheet.getSheetName();
    String refSheetNameParent = (String) params.get("refSheetNameParent");
    int lastRow = (int) params.get("lastRow");
    boolean isGenerateData = (boolean) params.getOrDefault("isGenerateData", true);

    if (isGenerateData) {
      AtomicInteger dataRowStart = new AtomicInteger(0);
      for (Map<String, Object> ref : refCategories) {
        List<Map<String, Object>> _categories =
            categories.stream()
                .filter(
                    f ->
                        f.get("parentCode")
                            .equals(ref.get("id") != null ? ref.get("id") : ref.get("code")))
                .collect(Collectors.toList());
        if (!_categories.isEmpty()) {
          int initRowStart = dataRowStart.get() + 1;
          _categories.forEach(
              c -> {
                AtomicInteger colStart = new AtomicInteger(0);
                Row row = getRowWithCreate(srcSheet, dataRowStart.get());
                keyList.forEach(
                    k -> {
                      Cell cell = getCellWithCreate(row, colStart.getAndIncrement());
                      setDataToCell(c.get(k), cell);
                    });
                dataRowStart.getAndIncrement();
              });

          XSSFName nameTmp = wb.createName();
          nameTmp.setNameName(
              srcSheetName + '_' + (ref.get("id") != null ? ref.get("id") : ref.get("code")));
          nameTmp.setRefersToFormula(
              srcSheetName + "!$B$" + initRowStart + ":$B$" + dataRowStart.get());
        }
      }
    }

    String refFormulas = "";
    if (StringUtils.isNotBlank(refSheetNameParent)) {
      String LastColumnAddress =
          CellReference.convertNumToColString(
              wb.getSheet(refSheetNameParent).getRow(0).getLastCellNum() - 1);
      refFormulas = "INDEX(" + refSheetNameParent + "!$B$1:$B$" + lastRow;
      refFormulas =
          refFormulas
              + ",MATCH(INDIRECT(ADDRESS(ROW(),COLUMN()-1)),"
              + refSheetNameParent
              + "!$"
              + LastColumnAddress
              + "$1:$"
              + LastColumnAddress
              + "$"
              + lastRow
              + ",0))";
    } else {
      refFormulas = "INDEX(" + refSheetName + "!$A$1:$A$" + refCategories.size();
      if (params.get("multiColumnMap") != null && params.get("multiColumnMap").toString() != null) {
        refFormulas =
            refFormulas
                + ",MATCH(CONCATENATE(INDIRECT(ADDRESS(ROW(),COLUMN()-2)),\"_\",INDIRECT(ADDRESS(ROW(),COLUMN()-1))),"
                + refSheetName
                + "!$D$1:$D$"
                + refCategories.size()
                + ",0))";
      } else
        refFormulas =
            refFormulas
                + ",MATCH(INDIRECT(ADDRESS(ROW(),COLUMN()-1)),"
                + refSheetName
                + "!$B$1:$B$"
                + refCategories.size()
                + ",0))";
    }

    String formulas = "INDIRECT(\"" + srcSheetName + "_\"&" + refFormulas + ")";
    setXSSFValidation(desSheet, formulas, params);

    wb.setSheetHidden(wb.getSheetIndex(srcSheet), true);
    srcSheet.protectSheet(pass);
  }

  public void createCategorySheetInputOneInTwo(
      XSSFWorkbook wb,
      XSSFSheet srcSheet,
      XSSFSheet desSheet,
      List<Map<String, Object>> categories,
      Map<String, Object> params) {
    @SuppressWarnings("unchecked")
    List<String> keyList = (List<String>) params.get("keyList");
    String pass = (String) params.get("pass");
    boolean isGenerateData = (boolean) params.getOrDefault("isGenerateData", true);
    int firstCol = (int) params.get("firstCol");
    int lastCol = (int) params.get("lastCol");
    int firstRow = (int) params.get("firstRow");
    int lastRow = (int) params.get("lastRow");
    int colCheck = (int) params.get("colCheck");

    String columnName = CellReference.convertNumToColString(colCheck);
    if (isGenerateData) setDataToCategorySheet(srcSheet, categories, keyList);

    DataValidationHelper helper = desSheet.getDataValidationHelper();
    for (int row = firstRow; row <= lastRow; row++) {
      String formula =
          "IF($"
              + columnName
              + (row + 1)
              + "<>\"\","
              + srcSheet.getSheetName()
              + "!$B$"
              + (categories.size() + 10)
              + ":$B$"
              + (categories.size() + 50)
              + ","
              + srcSheet.getSheetName()
              + "!$B$1:$B$"
              + categories.size()
              + ")";
      DataValidationConstraint constraint = helper.createFormulaListConstraint(formula);
      DataValidation dataValidation =
          helper.createValidation(
              constraint, new CellRangeAddressList(row, row, firstCol, lastCol));
      if (dataValidation instanceof XSSFDataValidation) {
        dataValidation.setSuppressDropDownArrow(true);
        dataValidation.setShowErrorBox(true);
      } else {
        dataValidation.setSuppressDropDownArrow(false);
      }
      desSheet.addValidationData(dataValidation);
    }

    wb.setSheetHidden(wb.getSheetIndex(srcSheet), true);
    srcSheet.protectSheet(pass);
  }

  public <T> File setSingleDataToDynamicTemplate(
      T data, List<Integer> columnsToHide, String dynamicTitle, String template) {
    Class<?> classType = data.getClass();
    ExcelExportGeneralConfig config = classType.getAnnotation(ExcelExportGeneralConfig.class);
    if (config == null) {
      throw new CustomException("Lỗi config");
    }
    String templateName =
        template != null
            ? CommonConstant.EXCEL_TEMPLATE_FOLDER + template
            : this.getTemplateName(classType);
    File file = null;
    File templateFile = this.getTemplateFile(templateName);
    try (Workbook workbook = this.openWorkbook(templateFile)) {
      Sheet sheet = workbook.getSheetAt(0);
      if (dynamicTitle != null && !dynamicTitle.isEmpty()) {
        Row titleRow = sheet.getRow(1);
        if (titleRow == null) {
          titleRow = sheet.createRow(1);
        }
        Cell titleCell = titleRow.getCell(1);
        if (titleCell == null) {
          titleCell = titleRow.createCell(1);
        }
        titleCell.setCellValue(dynamicTitle);
      }
      if (columnsToHide != null && !columnsToHide.isEmpty()) {
        for (int columnIndex : columnsToHide) {
          hideColumn(sheet, columnIndex);
        }
      }
      this.setSingleDataToSheet(data, sheet, config.hasSampleRow());
      file = this.writeToFile(templateFile, workbook, config);
    } catch (Exception e) {
      log.error("" + e);
      if (templateFile != null) {
        boolean deleteSuccess = templateFile.delete();
        log.debug("Delete file success: " + deleteSuccess);
      }
      throw new CustomException(e.getMessage());
    } finally {
      System.gc();
    }
    return file;
  }

  public void createDropListColumnWithParam(
      XSSFWorkbook wb,
      XSSFSheet sheet,
      List<Map<String, Object>> categories,
      List<String> keyList,
      int row,
      int addRowNum,
      int col,
      XSSFSheet desSheet,
      String pass,
      String codeKey,
      String categoryCode,
      XSSFSheet tempSheet,
      Map<String, Object> params) {
    setDataToCategorySheet(sheet, categories, keyList, codeKey, categoryCode);
    int firstRow = (int) params.get("firstRow");
    int lastRow = (int) params.get("lastRow");
    Integer colCheck = (Integer) params.get("colCheck");
    if (colCheck != null && colCheck >= 0) {
      String columnName = CellReference.convertNumToColString(colCheck);
      for (int r = firstRow; r <= lastRow; r++) {
        String formula =
            "IF($"
                + columnName
                + (r + 1)
                + "<>\"\",'"
                + sheet.getSheetName()
                + "'!$B$"
                + (categories.size() + 10)
                + ":$B$"
                + (categories.size() + 50)
                + ",'"
                + sheet.getSheetName()
                + "'!$B$1:$B$"
                + categories.size()
                + ")";
        setXSSFListValidation(desSheet, formula.toString(), r, r, col, col);
      }
    } else {
      StringBuilder formula =
          new StringBuilder("'")
              .append(sheet.getSheetName())
              .append("'!$B$1:$B$")
              .append(categories.size());
      setXSSFListValidation(desSheet, formula.toString(), row, row + addRowNum, col, col);
    }
    if (col >= 0) {
      String colName = CellReference.convertNumToColString(col);
      for (int i = row; i <= (row + addRowNum); i++) {
        Row formulaRow = getRowWithCreate(tempSheet, i);
        Cell formulaCell = getCellWithCreate(formulaRow, col);
        StringBuilder f = new StringBuilder("IFERROR(VLOOKUP(");
        f.append("'")
            .append(desSheet.getSheetName())
            .append("'!")
            .append(colName)
            .append(i + 1)
            .append(",'")
            .append(sheet.getSheetName())
            .append("'!$B$1:$C$")
            .append(categories.size())
            .append(",")
            .append(2)
            .append(",")
            .append("FALSE),\"\")");
        formulaCell.setCellFormula(f.toString());
        XSSFFormulaEvaluator formulaEvaluator = wb.getCreationHelper().createFormulaEvaluator();
        formulaEvaluator.evaluateFormulaCell(formulaCell);
      }
    }
    wb.setSheetHidden(wb.getSheetIndex(sheet), true);
    sheet.protectSheet(pass);
  }

  public void mergeByRegionV2(int rowNum, int from, int to, Sheet sheet) {
    if (rowNum > 0 && from > 0 && to > 0 && to > from) {
      String startRange = getRowNameByIndex(rowNum, from, sheet);
      String endRange = getRowNameByIndex(rowNum, to, sheet);
      if (!startRange.isEmpty() && !endRange.isEmpty())
        sheet.addMergedRegion(CellRangeAddress.valueOf(startRange + ":" + endRange));
    }
  }

  public <T> File setSingleDataToTemplateMultiSheet(
      T data, List<Integer> columnsToHide, String dynamicTitle, List<Integer> removeCols) {
    Class<?> classType = data.getClass();
    ExcelExportGeneralConfig config = classType.getAnnotation(ExcelExportGeneralConfig.class);
    if (config == null) {
      throw new CustomException("Lỗi config");
    }
    String templateName = this.getTemplateName(classType);
    File file = null;
    File templateFile = this.getTemplateFile(templateName);
    try (Workbook workbook = this.openWorkbook(templateFile)) {
      final Field[] fields = classType.getDeclaredFields();
      ExcelExportForm fa;
      for (Field field : fields) {
        if (field.isAnnotationPresent(ExcelExportForm.class)) {
          fa = field.getAnnotation(ExcelExportForm.class);
          if (List.class.isAssignableFrom(field.getType())) {
            if (Objects.requireNonNull(fa).compareSheetName()) {
              Sheet sheet = workbook.getSheetAt(fa.sheetIndex());
              if (dynamicTitle != null && !dynamicTitle.isEmpty()) {
                Row titleRow = sheet.getRow(1);
                if (titleRow == null) {
                  titleRow = sheet.createRow(1);
                }
                Cell titleCell = titleRow.getCell(1);
                if (titleCell == null) {
                  titleCell = titleRow.createCell(1);
                }
                titleCell.setCellValue(dynamicTitle);
              }
              if (columnsToHide != null && !columnsToHide.isEmpty()) {
                for (int columnIndex : columnsToHide) {
                  hideColumn(sheet, columnIndex);
                }
              }

              if (removeCols != null && !removeCols.isEmpty()) {
                for (int columnIndex : removeCols) {
                  removeColumn(sheet, columnIndex);
                }
              }
              this.setSingleDataToSheet(data, sheet, config.hasSampleRow(), true);
            }
          }
        }
      }
      file = this.writeToFile(templateFile, workbook, config);
    } catch (Exception e) {
      log.error("" + e);
      if (templateFile != null) {
        boolean deleteSuccess = templateFile.delete();
        log.debug("Delete file success: " + deleteSuccess);
      }
      throw new CustomException(e.getMessage());
    } finally {
      System.gc();
    }
    return file;
  }

  public void createDropListColumnAndReferToUnit(
      XSSFWorkbook wb,
      XSSFSheet sheet,
      List<Map<String, Object>> categories,
      List<String> keyList,
      int row,
      int addRowNum,
      int col,
      XSSFSheet desSheet,
      String pass,
      String codeKey,
      String categoryCode,
      XSSFSheet tempSheet) {
    setDataToCategorySheet(sheet, categories, keyList, codeKey, categoryCode);
    StringBuilder formula =
        new StringBuilder("'")
            .append(sheet.getSheetName())
            .append("'!$B$1:$B$")
            .append(categories.size());
    setXSSFListValidation(desSheet, formula.toString(), row, row + addRowNum, col, col);
    if (col >= 0) {
      String colName = CellReference.convertNumToColString(col);
      for (int i = row; i <= (row + addRowNum); i++) {
        Row formulaRow = getRowWithCreate(tempSheet, i);
        Cell formulaCell = getCellWithCreate(formulaRow, col);
        StringBuilder f = new StringBuilder("IFERROR(VLOOKUP(");
        f.append("'")
            .append(desSheet.getSheetName())
            .append("'!")
            .append(colName)
            .append(i + 1)
            .append(",'")
            .append(sheet.getSheetName())
            .append("'!$B$1:$F$") // name(B)-tinh(C)-huyen(D)-xa(E)-DM_?(F)
            .append(categories.size())
            .append(",")
            .append(5) // 5
            .append(",")
            .append("FALSE),\"\")");
        formulaCell.setCellFormula(f.toString());
        XSSFFormulaEvaluator formulaEvaluator = wb.getCreationHelper().createFormulaEvaluator();
        formulaEvaluator.evaluateFormulaCell(formulaCell);
      }
      wb.setForceFormulaRecalculation(true);
    }
    wb.setSheetHidden(wb.getSheetIndex(sheet), true);
    sheet.protectSheet(pass);
  }

  // handle sheet import
  private String generateNormalImportFormula(Map<String, Object> param) {
    String typingSheetName = mapGet(param, "typingSheetName", "main");
    int rowDistance = mapGet(param, "rowDistance", 0);
    int colDistance = mapGet(param, "colDistance", 0);

    int firstRow = mapGet(param, "firstRow", 0);
    int dataLength = mapGet(param, "dataLength", 1);
    String dataRage =
        mapGet(
            param,
            "dataRage",
            String.format("$B$%s:$Z%s", firstRow + 1, firstRow + 1 + dataLength));

    return String.format(
        "IFERROR(IF(INDEX('%s'!%s,ROW()+%d,COLUMN()+%d)=0,\"\","
            + "INDEX('%s'!%s,ROW()+%d,COLUMN()+%d)),\"\")",
        typingSheetName,
        dataRage,
        rowDistance,
        colDistance,
        typingSheetName,
        dataRage,
        rowDistance,
        colDistance);
  }

  private void applyFormulaToRange(
      XSSFSheet sheet, int fromRow, int toRow, int colIndex, String formula) {
    for (int i = fromRow; i <= toRow; i++) {
      Row row = sheet.getRow(i);
      if (row == null) row = sheet.createRow(i);
      Cell cell = row.createCell(colIndex);
      cell.setCellFormula(formula);
    }
  }

  @SuppressWarnings("unchecked")
  private <T> T mapGet(Map<String, Object> map, String key, T defaultValue) {
    return (T) map.getOrDefault(key, defaultValue);
  }

  public <T> File setSingleDataToTemplateGrouped(T data, GroupSpec spec) {
    return setSingleDataToTemplateGrouped(data, null, null, null, spec);
  }

  public <T> File setSingleDataToTemplateGrouped(
      T data,
      String dynamicTitle,
      String dynamicSheetName,
      List<Integer> columnsToRemove,
      GroupSpec spec) {
    Class<?> classType = data.getClass();
    ExcelExportGeneralConfig config = classType.getAnnotation(ExcelExportGeneralConfig.class);
    if (config == null) throw new CustomException("Lỗi config");

    String templateName = getTemplateName(classType);
    File templateFile = getTemplateFile(templateName);

    try (Workbook wb = openWorkbook(templateFile)) {
      Sheet sheet = wb.getSheetAt(0);
      if (StringUtils.isNotBlank(dynamicSheetName)) wb.setSheetName(0, dynamicSheetName);
      if (StringUtils.isNotBlank(dynamicTitle)) {
        Row r = sheet.getRow(1) != null ? sheet.getRow(1) : sheet.createRow(1);
        Cell c = r.getCell(1) != null ? r.getCell(1) : r.createCell(1);
        c.setCellValue(dynamicTitle);
      }

      // list có chèn "nhóm ảo" trong bộ nhớ
      if (spec != null && spec.isEnabled()) {
        injectGroupHeaderRowsIntoDataList(data, spec);
      }

      // Ghi dữ liệu
      setSingleDataToSheet(data, sheet, config.hasSampleRow());

      // Xoá cột theo menuType
      if (columnsToRemove != null && !columnsToRemove.isEmpty()) {
        List<Integer> sorted = new ArrayList<>(columnsToRemove);
        sorted.sort(Comparator.reverseOrder());
        for (int col : sorted) removeColumn(sheet, col);
      }

      // Merge ngang các hàng tiêu đề nhóm sau khi cột đã bị xoá
      if (spec != null && spec.isEnabled()) {
        int fromCol = spec.getFromCol() != null ? spec.getFromCol() : 0;
        int toCol =
            spec.getToCol() != null ? spec.getToCol() : detectLastDataCol(sheet, spec.getFromRow());
        mergeSectionRows(sheet, config.startRow(), fromCol, toCol);
        removeColumn(sheet, 99); // xoa cột phụ communeName
      }

      return writeToFile(templateFile, wb, config);
    } catch (Exception e) {
      if (templateFile != null) templateFile.delete();
      throw new CustomException(e.getMessage());
    }
  }

  private int detectLastDataCol(Sheet sheet, int startRow) {
    int maxCol = -1;

    int[] probeRows = new int[] {startRow - 2, startRow - 1, startRow, startRow + 1};
    for (int rIdx : probeRows) {
      if (rIdx < 0 || rIdx > sheet.getLastRowNum()) continue;
      Row r = sheet.getRow(rIdx);
      if (r == null) continue;

      short last = r.getLastCellNum();
      if (last > 0) {
        maxCol = Math.max(maxCol, last - 1);
      }
    }
    return (maxCol >= 0) ? maxCol : getLastColumnIndex(sheet);
  }

  private void mergeSectionRows(Sheet sheet, int startRow, int fromCol, int toCol) {
    DataFormatter df = new DataFormatter();
    for (int r = startRow; r <= sheet.getLastRowNum(); r++) {
      Row row = sheet.getRow(r);
      if (row == null) continue;

      String base = df.formatCellValue(row.getCell(fromCol));
      if (StringUtils.isBlank(base)) continue;

      boolean isSection = true;
      for (int c = fromCol + 1; c <= toCol; c++) {
        String v = df.formatCellValue(row.getCell(c));
        if (!base.equals(v)) {
          isSection = false;
          break;
        }
      }
      if (!isSection) continue;

      // xoá text cột startCol + 1..toCol, giữ cột startCol
      for (int c = fromCol + 1; c <= toCol; c++) {
        Cell cell = row.getCell(c);
        if (cell != null) cell.setBlank();
      }
      // merge
      sheet.addMergedRegion(new CellRangeAddress(r, r, fromCol, toCol));

      CellStyle style = sheet.getWorkbook().createCellStyle();
      Font font = sheet.getWorkbook().createFont();
      font.setBold(true);
      style.setFont(font);
      style.setAlignment(HorizontalAlignment.LEFT);
      style.setBorderTop(BorderStyle.THIN);
      style.setBorderBottom(BorderStyle.THIN);
      style.setBorderLeft(BorderStyle.THIN);
      style.setBorderRight(BorderStyle.THIN);

      row.getCell(fromCol).setCellStyle(style);
    }
  }

  @SuppressWarnings("unchecked")
  private <T> void injectGroupHeaderRowsIntoDataList(T data, GroupSpec spec)
      throws IllegalAccessException, ReflectiveOperationException {

    // Chỉ lấy field vừa có @ExcelExportForm vừa là Collection/List
    Field listField =
        Arrays.stream(data.getClass().getDeclaredFields())
            .filter(
                f ->
                    f.isAnnotationPresent(ExcelExportForm.class)
                        && Collection.class.isAssignableFrom(f.getType()))
            .findFirst()
            .orElseThrow(() -> new CustomException("Không tìm thấy list data"));

    listField.setAccessible(true);
    List<Object> rows = (List<Object>) listField.get(data);
    if (rows == null || rows.isEmpty()) return;

    List<Object> result = new ArrayList<>(rows.size() + 16);
    Object prevKey = null;

    for (Object row : rows) {
      Object key = FieldUtils.readField(row, spec.getKeyField(), true);
      if (!Objects.equals(prevKey, key)) {
        Object header = row.getClass().getDeclaredConstructor().newInstance();
        FieldUtils.writeField(
            header,
            spec.getTitleField(),
            String.format(spec.getTitleFormat(), key == null ? "" : key.toString()),
            true);
        result.add(header);
        prevKey = key;
      }
      FieldUtils.writeField(row, spec.getTitleField(), null, true);
      result.add(row);
    }
    listField.set(data, result);
  }
}
