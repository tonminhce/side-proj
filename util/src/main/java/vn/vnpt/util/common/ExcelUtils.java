package vn.vnpt.util.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalField;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.RegionUtil;
import org.apache.poi.xssf.usermodel.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import vn.vnpt.util.common.excel.common.ExcelType;
import vn.vnpt.util.exception.CustomException;
import vn.vnpt.util.exception.UpdateException;

@Slf4j
public final class ExcelUtils {

  @Value("${excel.sheet.error}")
  private String folderTemp;

  public static Sheet createSimpleSheet(
      Workbook workbook,
      String sheetName,
      boolean autoResize,
      List<?> rawData,
      ObjectMapper objectMapper) {
    Sheet sheet = workbook.createSheet(sheetName);
    List<Map<String, Object>> data =
        rawData.stream()
            .map(e -> objectMapper.convertValue(e, new TypeReference<Map<String, Object>>() {}))
            .toList();

    if (!data.isEmpty()) {
      int colCount = data.getFirst().size();
      Row headerRow =
          createTitleRow(workbook, sheet, new ArrayList<>(data.getFirst().keySet()), null, null);

      // Fill cell data
      int rowNum = 1;
      for (Map<String, Object> rowData : data) {
        rowData.values().removeAll(Collections.singleton(null));
        Row row = sheet.createRow(rowNum);
        for (int colNum = 0; colNum < colCount; colNum++) {
          String colName = headerRow.getCell(colNum).getStringCellValue();
          String value = rowData.get(colName) == null ? "" : rowData.get(colName).toString();
          row.createCell(colNum).setCellValue(value);
        }
        rowNum++;
      }

      if (autoResize) {
        // Resize all columns to fit the content size
        for (int i = 0; i < colCount; i++) {
          sheet.autoSizeColumn(i);
        }
      }
    }
    return sheet;
  }

  public static Row createTitleRow(
      Workbook workbook,
      Sheet sheet,
      List<String> columnNames,
      @Nullable Short bg,
      @Nullable Short fg) {
    Row headerRow = sheet.createRow(0);
    AtomicInteger idx = new AtomicInteger(0);
    CellStyle style = workbook.createCellStyle();
    Font font = workbook.createFont();
    font.setFontHeightInPoints((short) 14);
    font.setBold(true);
    style.setFont(font);

    if (bg != null) {
      style.setFillBackgroundColor(bg);
    }
    if (fg != null) {
      style.setFillForegroundColor(fg);
      style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
    }

    columnNames.forEach(
        columnName -> {
          Cell cell = headerRow.createCell(idx.getAndIncrement());
          cell.setCellValue(columnName);
          cell.setCellStyle(style);
        });

    for (int i = 0; i < columnNames.size(); i++) {
      sheet.autoSizeColumn(i);
    }
    return headerRow;
  }

  public static File writeToFile(File file, Workbook wb) {
    try (FileOutputStream outputStream = new FileOutputStream(file)) {
      // Init all cell format with formula
      wb.write(outputStream);
    } catch (IOException e) {
      log.error(e.getMessage());
      throw new CustomException(e.getMessage());
    }
    return file;
  }

  public static void writeDataToCell(
      Workbook workbook,
      Sheet sheet,
      Object value,
      Integer rowIndex,
      Integer cellIndex,
      XSSFFont font) {
    Row headerRow = getRowWithCreate(sheet, rowIndex);

    CellStyle cellStyle = createAllBorderCell(workbook);

    Cell cell = getCellWithCreate(headerRow, cellIndex);
    cell.setCellStyle(cellStyle);

    if (font != null) {
      cellStyle.setFont(font);
    }

    if (value instanceof Date) {
      cell.setCellValue((Date) value);
    }
    if (value instanceof Double
        || value instanceof BigDecimal
        || value instanceof Integer
        || value instanceof BigInteger) {
      BigDecimal tmp = new BigDecimal(value.toString());
      cell.setCellValue(tmp.doubleValue());
    } else if (value instanceof String) {
      cell.setCellValue((String) value);
    } else if (value instanceof Boolean) {
      cell.setCellValue((Boolean) value);
    }
  }

  public static void writeDataToCellWithStyle(
      Workbook workbook,
      Sheet sheet,
      Object value,
      Integer rowIndex,
      Integer cellIndex,
      XSSFFont font,
      CellStyle cellStyle) {
    Row headerRow = getRowWithCreate(sheet, rowIndex);

    Cell cell = getCellWithCreate(headerRow, cellIndex);
    cell.setCellStyle(cellStyle);

    if (font != null) {
      cellStyle.setFont(font);
    }

    if (value instanceof Date) {
      cell.setCellValue((Date) value);
    }
    if (value instanceof Double
        || value instanceof BigDecimal
        || value instanceof Integer
        || value instanceof BigInteger) {
      BigDecimal tmp = new BigDecimal(value.toString());
      cell.setCellValue(tmp.doubleValue());
    } else if (value instanceof String) {
      cell.setCellValue((String) value);
    } else if (value instanceof Boolean) {
      cell.setCellValue((Boolean) value);
    }
  }

  public static Cell getCellWithCreate(Row row, int index) {
    Cell cell = row.getCell(index);

    if (cell == null) {
      cell = row.createCell(index);
    }
    return cell;
  }

  public static Row getRowWithCreate(Sheet sheet, int rowIndex) {
    Row row = sheet.getRow(rowIndex);
    if (row == null) {
      row = sheet.createRow(rowIndex);
    }
    return row;
  }

  public static Row customeCreateTitleRow(
      Workbook workbook,
      Sheet sheet,
      List<String> columnNames,
      Integer rowIndex,
      Integer cellIndex,
      @Nullable XSSFColor bg) {
    //        Row headerRow = sheet.getRow(rowIndex) == null ? sheet.createRow(rowIndex) :
    // sheet.getRow(rowIndex);
    Row headerRow = getRowWithCreate(sheet, rowIndex);

    AtomicInteger idx = new AtomicInteger(cellIndex);

    XSSFCellStyle cellStyle = createAllBorderCell(workbook);
    cellStyle.setVerticalAlignment(VerticalAlignment.CENTER);
    cellStyle.setFillBackgroundColor(IndexedColors.OLIVE_GREEN.getIndex());
    cellStyle.setFillForegroundColor(IndexedColors.LAVENDER.index);

    if (bg != null) {
      cellStyle.setFillForegroundColor(bg);
      cellStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
    }

    XSSFFont font = createTimeNewRomanFont(workbook);
    cellStyle.setFont(font);

    columnNames.forEach(
        columnName -> {
          Cell cell = headerRow.createCell(idx.getAndIncrement());
          cell.setCellValue(columnName);
          cell.setCellStyle(cellStyle);
        });

    return headerRow;
  }

  public static XSSFCellStyle createStyle(Workbook workbook, boolean isBold, boolean isBorder) {

    XSSFCellStyle cellStyle = (XSSFCellStyle) workbook.createCellStyle();
    if (isBorder) {
      cellStyle.setBorderBottom(BorderStyle.THIN);
      cellStyle.setBorderLeft(BorderStyle.THIN);
      cellStyle.setBorderRight(BorderStyle.THIN);
      cellStyle.setBorderTop(BorderStyle.THIN);
    }

    XSSFFont font = (XSSFFont) workbook.createFont();
    font.setFontName("Times New Roman");
    font.setBold(isBold);
    cellStyle.setFont(font);
    cellStyle.setVerticalAlignment(VerticalAlignment.CENTER);
    cellStyle.setWrapText(true);
    return cellStyle;
  }

  public static XSSFCellStyle createStyleWithItalic(
      Workbook workbook, boolean isBold, boolean isBorder, boolean isItalic) {

    XSSFCellStyle cellStyle = (XSSFCellStyle) workbook.createCellStyle();
    if (isBorder) {
      cellStyle.setBorderBottom(BorderStyle.THIN);
      cellStyle.setBorderLeft(BorderStyle.THIN);
      cellStyle.setBorderRight(BorderStyle.THIN);
      cellStyle.setBorderTop(BorderStyle.THIN);
    }

    XSSFFont font = (XSSFFont) workbook.createFont();
    font.setFontName("Times New Roman");
    font.setBold(isBold);
    font.setItalic(isItalic);
    cellStyle.setFont(font);
    cellStyle.setVerticalAlignment(VerticalAlignment.CENTER);

    return cellStyle;
  }

  public static XSSFCellStyle createStyleWithWrapText(
      Workbook workbook, boolean isBold, boolean isBorder) {

    XSSFCellStyle cellStyle = (XSSFCellStyle) workbook.createCellStyle();
    if (isBorder) {
      cellStyle.setBorderBottom(BorderStyle.THIN);
      cellStyle.setBorderLeft(BorderStyle.THIN);
      cellStyle.setBorderRight(BorderStyle.THIN);
      cellStyle.setBorderTop(BorderStyle.THIN);
    }

    XSSFFont font = (XSSFFont) workbook.createFont();
    font.setFontName("Times New Roman");
    font.setBold(isBold);
    cellStyle.setFont(font);
    cellStyle.setVerticalAlignment(VerticalAlignment.CENTER);
    cellStyle.setWrapText(true);

    return cellStyle;
  }

  public static XSSFCellStyle createStyleWithCenter(
      XSSFCellStyle cellStyle, HorizontalAlignment alignment) {
    cellStyle.setAlignment(alignment);
    return cellStyle;
  }

  public static void setAllBorderRegion(Sheet sheet, CellRangeAddress cellAddresses) {
    RegionUtil.setBorderTop(BorderStyle.THIN, cellAddresses, sheet);
    RegionUtil.setBorderBottom(BorderStyle.THIN, cellAddresses, sheet);
    RegionUtil.setBorderLeft(BorderStyle.THIN, cellAddresses, sheet);
    RegionUtil.setBorderRight(BorderStyle.THIN, cellAddresses, sheet);
  }

  public static XSSFCellStyle createAllBorderCell(Workbook workbook) {
    XSSFCellStyle cellStyle = (XSSFCellStyle) workbook.createCellStyle();
    cellStyle.setBorderBottom(BorderStyle.THIN);
    cellStyle.setBorderLeft(BorderStyle.THIN);
    cellStyle.setBorderRight(BorderStyle.THIN);
    cellStyle.setBorderTop(BorderStyle.THIN);
    cellStyle.setWrapText(true);
    cellStyle.setAlignment(HorizontalAlignment.CENTER);
    return cellStyle;
  }

  public static XSSFCellStyle createAlignmentCenter(Workbook workbook, int type) {
    XSSFCellStyle cellStyle = (XSSFCellStyle) workbook.createCellStyle();
    cellStyle.setAlignment(type == 1 ? HorizontalAlignment.LEFT : HorizontalAlignment.CENTER);
    cellStyle.setVerticalAlignment(VerticalAlignment.CENTER);

    cellStyle.setBorderBottom(BorderStyle.THIN);
    cellStyle.setBorderLeft(BorderStyle.THIN);
    cellStyle.setBorderRight(BorderStyle.THIN);
    cellStyle.setBorderTop(BorderStyle.THIN);

    XSSFFont font = (XSSFFont) workbook.createFont();
    font.setFontName("Times New Roman");
    //        font.setBold(isBold);
    cellStyle.setFont(font);

    return cellStyle;
  }

  public static XSSFFont createTimeNewRomanFont(Workbook workbook) {
    XSSFFont font = (XSSFFont) workbook.createFont();
    font.setFontName("Times New Roman");
    font.setBold(true);
    return font;
  }

  public static XSSFFont createTimeNewRomanFontItalic(Workbook workbook) {
    XSSFFont font = (XSSFFont) workbook.createFont();
    font.setFontName("Times New Roman");
    font.setItalic(true);
    return font;
  }

  public static void mergeCellWithSameValue(
      Sheet sheet, int startRowIdx, int endRowIdx, int colIdx) {
    // Merge cell
    int firstRow = startRowIdx;
    int lastRow = startRowIdx;
    for (int rowIdx = startRowIdx; rowIdx <= endRowIdx - 1; rowIdx++) {
      Cell cell = sheet.getRow(rowIdx).getCell(colIdx);
      String currentValue = handleCell(cell);
      String nextRowCellValue;
      if (rowIdx < endRowIdx - 1) {
        if (sheet.getRow(rowIdx + 1) == null) {
          sheet.createRow(rowIdx + 1);
        }
        Cell nextRowCell = sheet.getRow(rowIdx + 1).getCell(colIdx);
        nextRowCellValue = handleCell(nextRowCell);
      } else {
        nextRowCellValue = null;
      }
      boolean isNotSameValue =
          !StringUtils.equals(currentValue, nextRowCellValue)
              || StringUtils.isBlank(currentValue)
              || StringUtils.isBlank(nextRowCellValue);
      if (isNotSameValue) {
        if (firstRow != lastRow) {
          CellRangeAddress cellRangeAddress =
              new CellRangeAddress(firstRow, lastRow, colIdx, colIdx);
          sheet.addMergedRegion(cellRangeAddress);
        }
        firstRow = rowIdx + 1;
      }
      // always update last row
      lastRow = rowIdx + 1;
    }
  }

  public static void mergeCellWithSameValue1(
      Sheet sheet, int startRowIdx, int endRowIdx, int colIdx, int col1, int col2) {
    // Merge cell
    int firstRow = startRowIdx;
    int lastRow = startRowIdx;
    for (int rowIdx = startRowIdx; rowIdx <= endRowIdx - 1; rowIdx++) {
      Cell cell = sheet.getRow(rowIdx).getCell(colIdx);
      String currentValue = handleCell(cell);
      String nextRowCellValue;
      if (rowIdx < endRowIdx - 1) {
        Cell nextRowCell = sheet.getRow(rowIdx + 1).getCell(colIdx);
        nextRowCellValue = handleCell(nextRowCell);
      } else {
        nextRowCellValue = null;
      }
      boolean isNotSameValue =
          !StringUtils.equals(currentValue, nextRowCellValue)
              || StringUtils.isBlank(currentValue)
              || StringUtils.isBlank(nextRowCellValue);
      if (isNotSameValue) {
        if (firstRow != lastRow) {
          for (int i = col1; i <= col2; i++) {
            CellRangeAddress cellRangeAddress = new CellRangeAddress(firstRow, lastRow, i, i);
            sheet.addMergedRegion(cellRangeAddress);
          }
        }
        firstRow = rowIdx + 1;
      }
      // always update last row
      lastRow = rowIdx + 1;
    }
  }

  private static String handleCell(Cell cell) {
    if (cell == null) {
      return null;
    }
    DataFormatter formatter = new DataFormatter();
    if (cell.getCellType() == CellType.FORMULA) {
      return switch (cell.getCachedFormulaResultType()) {
        case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
        case NUMERIC -> String.valueOf(cell.getNumericCellValue());
        case STRING -> String.valueOf(cell.getRichStringCellValue());
        default -> formatter.formatCellValue(cell);
      };
    } else {
      return formatter.formatCellValue(cell);
    }
  }

  public static byte[] createFileFromWorkbook(Workbook workbook) {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try {
      workbook.write(baos);
      byte[] result = baos.toByteArray();
      baos.close();
      return result;
    } catch (IOException ioException) {
      return new byte[0];
    }
  }

  public static HttpHeaders createExcelHeader(ExcelType type, String fileName) {
    HttpHeaders headers = new HttpHeaders();
    DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    String posfix =
        Instant.now().atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDateTime().format(formatter);

    String finalFileName = fileName + "_" + posfix + "." + type.toString().toLowerCase();
    ContentDisposition contentDisposition =
        ContentDisposition.builder("attachment").filename(finalFileName).build();
    headers.setContentDisposition(contentDisposition);
    headers.setContentType(type.getMediaType());
    return headers;
  }

  public static Workbook createWorkbook(ExcelType type) {
    return type == ExcelType.XLSX ? new XSSFWorkbook() : new HSSFWorkbook();
  }

  public static StringBuilder titleTuNgayDenNgay(LocalDate tuNgay, LocalDate denNgay) {
    StringBuilder stringBuilder = new StringBuilder();

    String tuNgayFormat =
        tuNgay != null ? tuNgay.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) : "";
    String denNgayFormat =
        denNgay != null ? denNgay.format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) : "";

    String ngayHienTai = DatetimeUtil.getCurrentDatetimeInFormat("dd/MM/yyyy");
    if (tuNgay == null && denNgay == null) {
      stringBuilder.append("TÍNH ĐẾN NGÀY ").append(ngayHienTai);
    } else if (tuNgay == null) {
      stringBuilder.append("TÍNH ĐẾN NGÀY ").append(denNgayFormat);
    } else if (denNgay == null) {
      stringBuilder
          .append("TỪ NGÀY ")
          .append(tuNgayFormat)
          .append(" ĐẾN NGÀY ")
          .append(ngayHienTai);
    } else {
      stringBuilder
          .append("TỪ NGÀY ")
          .append(tuNgayFormat)
          .append(" ĐẾN NGÀY ")
          .append(denNgayFormat);
    }

    return stringBuilder;
  }

  public static TimeDto getWeekInfo(LocalDate date) {
    WeekFields weekFields = WeekFields.of(Locale.getDefault());
    TemporalField woy = weekFields.weekOfWeekBasedYear();
    LocalDate now = LocalDate.now();

    TemporalField dayOfWeek = WeekFields.ISO.dayOfWeek();
    LocalDate fromDate =
        date != null
            ? date.with(dayOfWeek, dayOfWeek.range().getMinimum())
            : now.with(dayOfWeek, dayOfWeek.range().getMinimum());
    LocalDate toDate =
        date != null
            ? date.with(dayOfWeek, dayOfWeek.range().getMaximum())
            : now.with(dayOfWeek, dayOfWeek.range().getMaximum());

    Integer week = date != null ? date.get(woy) : now.get(woy);
    Integer year = date != null ? date.getYear() : now.getYear();

    return TimeDto.builder().week(week).year(year).fromDate(fromDate).toDate(toDate).build();
  }

  public static Comparator<Map<String, Object>> compareWithMap(String key1, String key2) {
    return (e1, e2) -> {
      if (e1.get(key1) != null && e2.get(key2) != null) {
        int value1 = Integer.parseInt(e1.get(key1).toString());
        int value2 = Integer.parseInt(e2.get(key2).toString());
        return Integer.compare(value1, value2);
      }
      return 0;
    };
  }

  public static void setDataToCell(Object value, Cell cell) {
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

  public static void writeListDataToExcel(
      Sheet sheetData,
      List<Map<String, Object>> listData,
      List<String> listKey,
      int rowDataIdx,
      int colDataIdx,
      boolean autoResize,
      CellStyle cellStyle,
      boolean isSTTColumm) {
    // Gọi lại phương thức gốc với giá trị mặc định cho defaultValueWhenNull
    writeListDataToExcel(
        sheetData,
        listData,
        listKey,
        rowDataIdx,
        colDataIdx,
        autoResize,
        cellStyle,
        isSTTColumm,
        "");
  }

  public static void writeListDataToExcel(
      Sheet sheetData,
      List<Map<String, Object>> listData,
      List<String> listKey,
      int rowDataIdx,
      int colDataIdx,
      @Nullable boolean autoResize,
      CellStyle cellStyle,
      boolean isSTTColumm,
      String defaultValueWhenNull) {
    XSSFCellStyle cellStyleCenterSTT = ExcelUtils.createStyle(sheetData.getWorkbook(), false, true);
    cellStyleCenterSTT.setAlignment(HorizontalAlignment.CENTER);

    AtomicInteger stt = new AtomicInteger(1);

    CellStyle cellStyleSTT =
        createStyleWithCenter((XSSFCellStyle) cellStyle, HorizontalAlignment.CENTER);

    for (Map<String, Object> emp : listData) {
      AtomicInteger colStart = new AtomicInteger(colDataIdx);
      Row row = getRowWithCreate(sheetData, rowDataIdx);
      Cell cellSTT = getCellWithCreate(row, colDataIdx);
      listKey.forEach(
          key -> {
            Cell cell = getCellWithCreate(row, colStart.getAndIncrement());
            setDataToCell(emp.get(key) == null ? defaultValueWhenNull : emp.get(key), cell);
            cell.setCellStyle(cellStyle);
          });

      if (isSTTColumm) {
        setDataToCell(stt.getAndIncrement(), cellSTT);
        cellSTT.setCellStyle(cellStyleSTT);
      }

      rowDataIdx++;
    }

    if (autoResize) {
      // Resize all columns to fit the content size
      for (int i = 0; i < listKey.size(); i++) {
        sheetData.autoSizeColumn(i);
      }
    }
  }

  public static StringBuilder titleDateWithValidate(
      LocalDate tuNgay, LocalDate denNgay, Integer nam) {
    StringBuilder kyBaoCao = new StringBuilder();
    if (nam == null) {
      throw new UpdateException("Vui lòng chọn 1 năm để xem báo cáo!");
    }

    if (tuNgay != null && denNgay != null) {
      Integer yearFromDate = tuNgay.getYear();
      Integer yearToDate = denNgay.getYear();

      String tuNgayFormat = tuNgay.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
      String denNgayFormat = denNgay.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));

      if (yearToDate.compareTo(yearFromDate) != 0) {
        throw new UpdateException("Vui lòng chọn từ ngày đến ngày trong cùng 1 năm!");
      }

      if (yearToDate.compareTo(nam) != 0) {
        throw new UpdateException("Vui lòng chọn khoảng thời gian trong năm để xem báo cáo!");
      }

      kyBaoCao.append("Từ ngày ").append(tuNgayFormat).append(" đến ngày ").append(denNgayFormat);
    } else {
      kyBaoCao.append("Năm ").append(nam);
    }

    return kyBaoCao;
  }

  public static String getParentCode(String tinh, String huyen, String xa, Integer capDonVi) {
    return (capDonVi != null && capDonVi == 2)
        ? tinh
        : (capDonVi != null && capDonVi == 3) ? huyen : xa;
  }

  public static void writeListDataToExcel_HGI(
      Workbook workbook,
      Sheet sheetData,
      List<Map<String, Object>> listData,
      List<String> listKey,
      int rowDataIdx,
      int colDataIdx,
      @Nullable boolean autoResize,
      CellStyle cellStyle,
      boolean isSTTColumm) {

    AtomicInteger stt = new AtomicInteger(1);
    DataFormat format = workbook.createDataFormat();
    XSSFCellStyle cellStyleSTT = ExcelUtils.createStyle(workbook, false, true);
    cellStyleSTT.setDataFormat(format.getFormat("0"));
    cellStyleSTT.setAlignment(HorizontalAlignment.CENTER);
    for (Map<String, Object> emp : listData) {
      AtomicInteger colStart = new AtomicInteger(colDataIdx);
      Row row = getRowWithCreate(sheetData, rowDataIdx);
      Cell cellSTT = getCellWithCreate(row, colDataIdx);
      listKey.forEach(
          key -> {
            Cell cell = getCellWithCreate(row, colStart.getAndIncrement());
            setDataToCell(emp.get(key), cell);
            cell.setCellStyle(cellStyle);
          });
      if (isSTTColumm) {
        setDataToCell(stt.getAndIncrement(), cellSTT);
        cellSTT.setCellStyle(cellStyleSTT);
      } else {
        setDataToCell("", cellSTT);
        cellSTT.setCellStyle(cellStyleSTT);
      }

      rowDataIdx++;
    }

    if (autoResize) {
      // Resize all columns to fit the content size
      for (int i = 0; i < listKey.size(); i++) {
        sheetData.autoSizeColumn(i);
      }
    }
  }

  public static void writeListDataToExcel_HGI1(
      Workbook workbook,
      Sheet sheetData,
      List<Map<String, Object>> listData,
      List<String> listKey,
      int rowDataIdx,
      int colDataIdx,
      @Nullable boolean autoResize,
      CellStyle cellStyle,
      boolean isSTTColumm) {

    AtomicInteger stt = new AtomicInteger(1);
    DataFormat format = workbook.createDataFormat();
    XSSFCellStyle cellStyleSTT = ExcelUtils.createStyle(workbook, false, true);
    cellStyleSTT.setDataFormat(format.getFormat("0"));
    cellStyleSTT.setAlignment(HorizontalAlignment.CENTER);
    for (Map<String, Object> emp : listData) {
      AtomicInteger colStart = new AtomicInteger(colDataIdx);
      Row row = getRowWithCreate(sheetData, rowDataIdx);
      Cell cellSTT = getCellWithCreate(row, colDataIdx);
      listKey.forEach(
          key -> {
            Cell cell = getCellWithCreate(row, colStart.getAndIncrement());
            setDataToCell(emp.get(key), cell);
            cell.setCellStyle(cellStyle);
          });
      if (isSTTColumm) {
        setDataToCell(stt.getAndIncrement(), cellSTT);
        cellSTT.setCellStyle(cellStyleSTT);
      } else {
        //                setDataToCell("", cellSTT);
        //                cellSTT.setCellStyle(cellStyleSTT);
      }

      rowDataIdx++;
    }

    if (autoResize) {
      // Resize all columns to fit the content size
      for (int i = 0; i < listKey.size(); i++) {
        sheetData.autoSizeColumn(i);
      }
    }
  }

  public static void writeListDataToExcelNumberFormat(
      Workbook workbook,
      Sheet sheetData,
      List<Map<String, Object>> listData,
      List<String> listKey,
      int rowDataIdx,
      int colDataIdx,
      @Nullable boolean autoResize,
      boolean isSTTColumm) {
    DataFormat format = workbook.createDataFormat();
    XSSFCellStyle cellStyleString = ExcelUtils.createStyle(workbook, false, true);
    XSSFCellStyle cellStyleNumber = ExcelUtils.createStyle(workbook, false, true);
    XSSFCellStyle cellStyleDecimal = ExcelUtils.createStyle(workbook, false, true);
    cellStyleNumber.setDataFormat(format.getFormat("#,##0"));
    cellStyleDecimal.setDataFormat(format.getFormat("#,##0.##########"));
    XSSFCellStyle[] cellStyleArray = {cellStyleString, cellStyleNumber, cellStyleDecimal};
    AtomicInteger stt = new AtomicInteger(1);
    XSSFCellStyle cellStyleSTT = ExcelUtils.createStyle(workbook, false, true);
    cellStyleSTT.setDataFormat(format.getFormat("0"));
    cellStyleSTT.setAlignment(HorizontalAlignment.CENTER);
    for (Map<String, Object> emp : listData) {
      AtomicInteger colStart = new AtomicInteger(colDataIdx);
      Row row = getRowWithCreate(sheetData, rowDataIdx);
      AtomicReference<Cell> cellSTT = new AtomicReference<>();
      //            Cell cellSTT = getCellWithCreate(row, colDataIdx);
      listKey.forEach(
          key -> {
            if ((key.isEmpty() || key.toUpperCase().equals("STT")) && cellSTT.get() == null) {
              cellSTT.set(getCellWithCreate(row, colStart.getAndIncrement()));
            } else {
              Cell cell = getCellWithCreate(row, colStart.getAndIncrement());
              setDataToCell(emp.get(key), cell);
              if (emp.get(key) instanceof Double
                  || emp.get(key) instanceof BigDecimal
                  || emp.get(key) instanceof Integer
                  || emp.get(key) instanceof BigInteger
                  || emp.get(key) instanceof Float) {
                String stringValue = emp.get(key).toString();
                BigDecimal bigDecimalValue = new BigDecimal(stringValue);
                if (bigDecimalValue
                        .setScale(0, RoundingMode.DOWN)
                        .compareTo(bigDecimalValue.setScale(0, RoundingMode.UP))
                    == 0) {
                  cell.setCellStyle(cellStyleArray[1]);
                } else {
                  cell.setCellStyle(cellStyleArray[2]);
                }
              } else {
                cell.setCellStyle(cellStyleArray[0]);
              }
            }
          });
      if (cellSTT.get() != null) {
        if (isSTTColumm) {
          setDataToCell(stt.getAndIncrement(), cellSTT.get());
          cellSTT.get().setCellStyle(cellStyleSTT);
        } else {
          setDataToCell("", cellSTT.get());
          cellSTT.get().setCellStyle(cellStyleSTT);
        }
      }
      rowDataIdx++;
    }

    if (autoResize) {
      // Resize all columns to fit the content size
      for (int i = 0; i < listKey.size(); i++) {
        sheetData.autoSizeColumn(i);
      }
    }
  }

  public static void writeListDataToExcelNoneSTT_HGI(
      Workbook workbook,
      Sheet sheetData,
      List<Map<String, Object>> listData,
      List<String> listKey,
      int rowDataIdx,
      int colDataIdx,
      @Nullable boolean autoResize,
      CellStyle cellStyle) {

    DataFormat format = workbook.createDataFormat();
    XSSFCellStyle cellStyleSTT = ExcelUtils.createStyle(workbook, false, true);
    cellStyleSTT.setDataFormat(format.getFormat("0"));
    cellStyleSTT.setAlignment(HorizontalAlignment.CENTER);
    for (Map<String, Object> emp : listData) {
      AtomicInteger colStart = new AtomicInteger(colDataIdx);
      Row row = getRowWithCreate(sheetData, rowDataIdx);
      listKey.forEach(
          key -> {
            Cell cell = getCellWithCreate(row, colStart.getAndIncrement());
            setDataToCell(emp.get(key), cell);
            cell.setCellStyle(cellStyle);
          });
      rowDataIdx++;
    }

    if (autoResize) {
      // Resize all columns to fit the content size
      for (int i = 0; i < listKey.size(); i++) {
        sheetData.autoSizeColumn(i);
      }
    }
  }

  public static void setCenterRegionWithCellAddresses(
      Sheet sheetData, CellRangeAddress cellAddresses) {
    Row row = getRowWithCreate(sheetData, cellAddresses.getLastRow());
    Cell cell = getCellWithCreate(row, cellAddresses.getLastColumn());

    XSSFCellStyle cellStyle = (XSSFCellStyle) cell.getCellStyle();
    cellStyle.setAlignment(HorizontalAlignment.CENTER);
    cellStyle.setVerticalAlignment(VerticalAlignment.CENTER);

    cellStyle.setBorderBottom(BorderStyle.THIN);
    cellStyle.setBorderLeft(BorderStyle.THIN);
    cellStyle.setBorderRight(BorderStyle.THIN);
    cellStyle.setBorderTop(BorderStyle.THIN);
    cell.setCellStyle(cellStyle);
  }

  public static void setColorRegion(
      Sheet sheetData, CellRangeAddress cellAddresses, @Nullable XSSFColor background) {
    Row row = getRowWithCreate(sheetData, cellAddresses.getFirstRow());
    Cell cell = getCellWithCreate(row, cellAddresses.getFirstColumn());

    XSSFCellStyle cellStyle = (XSSFCellStyle) cell.getCellStyle();
    if (background != null) {
      cellStyle.setFillForegroundColor(background);
      cellStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
    }

    cell.setCellStyle(cellStyle);
  }

  // @type: 1 - left : 2 - center
  public static XSSFCellStyle createStyle(
      Workbook workbook, boolean isBold, boolean isBorder, int type) {
    XSSFCellStyle cellStyle = (XSSFCellStyle) workbook.createCellStyle();

    cellStyle.setAlignment(
        type == 1
            ? HorizontalAlignment.LEFT
            : (type == 2 ? HorizontalAlignment.CENTER : HorizontalAlignment.RIGHT));
    cellStyle.setVerticalAlignment(VerticalAlignment.CENTER);

    if (isBorder) {
      cellStyle.setBorderBottom(BorderStyle.THIN);
      cellStyle.setBorderLeft(BorderStyle.THIN);
      cellStyle.setBorderRight(BorderStyle.THIN);
      cellStyle.setBorderTop(BorderStyle.THIN);
    }

    XSSFFont font = (XSSFFont) workbook.createFont();
    font.setFontName("Times New Roman");
    font.setBold(isBold);
    cellStyle.setFont(font);

    return cellStyle;
  }

  public static XSSFCellStyle createStyle(
      Workbook workbook, boolean isBold, boolean isBorder, int type, XSSFColor bg) {
    XSSFCellStyle cellStyle = (XSSFCellStyle) workbook.createCellStyle();

    cellStyle.setAlignment(
        type == 1
            ? HorizontalAlignment.LEFT
            : (type == 2 ? HorizontalAlignment.CENTER : HorizontalAlignment.RIGHT));
    cellStyle.setVerticalAlignment(VerticalAlignment.CENTER);

    if (isBorder) {
      cellStyle.setBorderBottom(BorderStyle.THIN);
      cellStyle.setBorderLeft(BorderStyle.THIN);
      cellStyle.setBorderRight(BorderStyle.THIN);
      cellStyle.setBorderTop(BorderStyle.THIN);
    }

    XSSFFont font = (XSSFFont) workbook.createFont();
    font.setFontName("Times New Roman");
    font.setBold(isBold);
    cellStyle.setFont(font);
    cellStyle.setFillForegroundColor(bg);
    cellStyle.setFillBackgroundColor(bg);
    cellStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
    return cellStyle;
  }

  public static XSSFCellStyle createStyle(
      Workbook workbook, boolean isBold, boolean isBorder, XSSFColor bg) {
    XSSFCellStyle cellStyle = (XSSFCellStyle) workbook.createCellStyle();
    if (isBorder) {
      cellStyle.setBorderBottom(BorderStyle.THIN);
      cellStyle.setBorderLeft(BorderStyle.THIN);
      cellStyle.setBorderRight(BorderStyle.THIN);
      cellStyle.setBorderTop(BorderStyle.THIN);
    }

    XSSFFont font = (XSSFFont) workbook.createFont();
    font.setFontName("Times New Roman");
    font.setBold(isBold);
    cellStyle.setFont(font);

    cellStyle.setFillForegroundColor(bg);
    cellStyle.setFillBackgroundColor(bg);
    cellStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
    return cellStyle;
  }

  public static XSSFCellStyle createStyle(
      Workbook workbook, boolean isBold, boolean isBorder, boolean isItalic, int type) {
    XSSFCellStyle cellStyle = (XSSFCellStyle) workbook.createCellStyle();

    cellStyle.setAlignment(
        type == 1
            ? HorizontalAlignment.LEFT
            : (type == 2 ? HorizontalAlignment.CENTER : HorizontalAlignment.RIGHT));
    cellStyle.setVerticalAlignment(VerticalAlignment.CENTER);

    if (isBorder) {
      cellStyle.setBorderBottom(BorderStyle.THIN);
      cellStyle.setBorderLeft(BorderStyle.THIN);
      cellStyle.setBorderRight(BorderStyle.THIN);
      cellStyle.setBorderTop(BorderStyle.THIN);
    }

    XSSFFont font = (XSSFFont) workbook.createFont();
    font.setFontName("Times New Roman");
    font.setBold(isBold);
    font.setItalic(isItalic);
    cellStyle.setFont(font);

    return cellStyle;
  }

  public static int getUnitLevel(String tinh, String huyen, String xa) {
    return (StringUtils.isBlank(huyen) && StringUtils.isBlank(xa))
        ? 1
        : (StringUtils.isNotBlank(huyen) && StringUtils.isBlank(xa)) ? 2 : 3;
  }

  public static String getReportTilleDateFromDate(LocalDate date) {
    StringBuilder string = new StringBuilder();
    string.append(" ngày ");
    string.append(date.getDayOfMonth());
    string.append(" tháng ");
    string.append(date.getMonthValue());
    string.append(" năm ");
    string.append(date.getYear());
    return string.toString();
  }

  public static void writeListDataToExcelCollection(
      Sheet sheetData,
      Collection<Map<String, Object>> listData,
      List<String> listKey,
      int rowDataIdx,
      int colDataIdx,
      @Nullable boolean autoResize,
      CellStyle cellStyle,
      boolean isSTTColumm) {

    AtomicInteger stt = new AtomicInteger(1);

    for (Map<String, Object> emp : listData) {
      AtomicInteger colStart = new AtomicInteger(colDataIdx);
      Row row = getRowWithCreate(sheetData, rowDataIdx);
      Cell cellSTT = getCellWithCreate(row, colDataIdx);
      listKey.forEach(
          key -> {
            Cell cell = getCellWithCreate(row, colStart.getAndIncrement());
            emp.get(key);
            if (emp.get(key) != null) {
              if (StringUtil.isUnicodeString(emp.get(key).toString())) {
                setDataToCell(emp.get(key), cell);
              } else {
                setDataToCell(Integer.parseInt(emp.get(key).toString()), cell);
              }
            }
            cell.setCellStyle(cellStyle);
          });

      if (isSTTColumm) {
        setDataToCell(stt.getAndIncrement(), cellSTT);
        cellSTT.setCellStyle(cellStyle);
      }

      rowDataIdx++;
    }

    if (autoResize) {
      // Resize all columns to fit the content size
      for (int i = 0; i < listKey.size(); i++) {
        sheetData.autoSizeColumn(i);
      }
    }
  }

  public static Row customeCreateTitleRow_HGI(
      Workbook workbook,
      Sheet sheet,
      List<String> columnNames,
      Integer rowIndex,
      Integer cellIndex,
      CellStyle cellStyle) {
    Row headerRow = getRowWithCreate(sheet, rowIndex);
    AtomicInteger idx = new AtomicInteger(cellIndex);
    XSSFFont font = createTimeNewRomanFont(workbook);
    cellStyle.setFont(font);
    columnNames.forEach(
        columnName -> {
          Cell cell = headerRow.createCell(idx.getAndIncrement());
          cell.setCellValue(columnName);
          cell.setCellStyle(cellStyle);
        });
    return headerRow;
  }

  public static <T> Predicate<T> distinctByKey(Function<? super T, ?> keyExtractor) {

    Map<Object, Boolean> seen = new ConcurrentHashMap<>();
    return t -> seen.putIfAbsent(keyExtractor.apply(t), Boolean.TRUE) == null;
  }

  public static StringBuilder titleFromYearToYear(Integer fromYear, Integer toYear) {
    StringBuilder stringBuilder = new StringBuilder();

    int now = LocalDate.now().getYear();
    if (fromYear == null && toYear == null) {
      stringBuilder.append("ĐẾN NĂM ").append(now);
    } else if (fromYear == null) {
      stringBuilder.append("TÍNH ĐẾN NĂM ").append(now);
    } else if (toYear == null) {
      stringBuilder.append("TỪ NĂM ").append(fromYear).append(" ĐẾN NĂM ").append(now);
    } else {
      stringBuilder.append("TỪ NĂM ").append(fromYear).append(" ĐẾN NĂM ").append(toYear);
    }

    return stringBuilder;
  }

  // Vd: Chuyển C2 về rowIndex = 1, cellIndex = 2
  public static int[] getRowAndColumnFromCellReference(String cellReference) {
    return new int[] {
      getRowFromCellReference(cellReference), getColumnFromCellReference(cellReference)
    };
  }

  public static int getRowFromCellReference(String cellReference) {
    String rowPart = cellReference.replaceAll("[^0-9]", "");
    return Integer.parseInt(rowPart) - 1;
  }

  public static int getColumnFromCellReference(String cellReference) {
    String columnPart = cellReference.replaceAll("[^A-Za-z]", "");

    int columnIndex = 0;
    for (int i = 0; i < columnPart.length(); i++) {
      columnIndex = columnIndex * 26 + (columnPart.charAt(i) - 'A' + 1);
    }

    return columnIndex - 1;
  }

  // Ghi giá trị cho ô bằng tên ô
  public static void setCellValueByCellReference(
      XSSFSheet sheet, String cellReference, String value) {
    int[] cellCoordinates = getRowAndColumnFromCellReference(cellReference);
    int rowIndex = cellCoordinates[0];
    int colIndex = cellCoordinates[1];

    Row row = getRowWithCreate(sheet, rowIndex);
    Cell cell = getCellWithCreate(row, colIndex);
    cell.setCellValue(value);
  }

  public static void setCellBold(
      XSSFWorkbook workbook, XSSFSheet sheet, int rowIndex, int colIndex, boolean isBold) {
    XSSFRow row = sheet.getRow(rowIndex);
    if (row != null) {
      XSSFCell cell = row.getCell(colIndex);
      if (cell != null) {
        XSSFCellStyle cellStyle = workbook.createCellStyle();
        cellStyle.cloneStyleFrom(cell.getCellStyle());

        XSSFFont font = workbook.createFont();
        font.setFontName("Times New Roman");
        font.setBold(isBold);
        cellStyle.setFont(font);

        cell.setCellStyle(cellStyle);
      }
    }
  }

  private static void protectCell(XSSFCell cell) {
    CellStyle style = cell.getSheet().getWorkbook().createCellStyle();
    style.setLocked(true); // Khóa ô
    cell.setCellStyle(style);
  }

  public static Object getCellValue(Sheet sheet, int rowIndex, int cellIndex) {
    Object cellValue;

    Row row = sheet.getRow(rowIndex);
    if (row != null) {
      Cell cell = row.getCell(cellIndex);
      if (cell != null) {
        switch (cell.getCellType()) {
          case STRING:
            cellValue = cell.getStringCellValue();
            break;
          case NUMERIC:
            if (DateUtil.isCellDateFormatted(cell)) {
              cellValue = cell.getDateCellValue();
            } else {
              cellValue = cell.getNumericCellValue();
            }
            break;
          case BOOLEAN:
            cellValue = cell.getBooleanCellValue();
            break;
          case FORMULA:
            FormulaEvaluator evaluator =
                cell.getSheet().getWorkbook().getCreationHelper().createFormulaEvaluator();
            cellValue = evaluator.evaluate(cell).getNumberValue();
            break;
          case BLANK:
            cellValue = "";
            break;
          default:
            cellValue = "Unknown cell type";
            break;
        }
      } else {
        cellValue = "Cell not found";
      }
    } else {
      cellValue = "Row not found";
    }

    return cellValue;
  }

  public static Map<String, Integer[]> generateColumnMapping(
      Map<String, Integer[]> commonMapping, int index) {
    Map<String, Integer[]> columnMapping = new HashMap<>();

    for (Map.Entry<String, Integer[]> entry : commonMapping.entrySet()) {
      String key = entry.getKey();
      Integer[] values = entry.getValue();

      if (index >= 0 && index < values.length) {
        columnMapping.put(key, new Integer[] {values[index], values[index]});
      } else {
        throw new IllegalArgumentException("Index out of bounds for values array");
      }
    }

    return columnMapping;
  }

  public static XSSFCellStyle createStyleAndCustomBorder(
      Workbook workbook,
      boolean isBold,
      boolean isItalic,
      boolean isBorder,
      @Nullable BorderStyle borderStyle) {
    XSSFCellStyle cellStyle = (XSSFCellStyle) workbook.createCellStyle();
    if (isBorder) {
      if (borderStyle != null) {
        cellStyle.setBorderBottom(borderStyle);
        cellStyle.setBorderLeft(borderStyle);
        cellStyle.setBorderRight(borderStyle);
        cellStyle.setBorderTop(borderStyle);
      } else {
        cellStyle.setBorderBottom(BorderStyle.THIN);
        cellStyle.setBorderLeft(BorderStyle.THIN);
        cellStyle.setBorderRight(BorderStyle.THIN);
        cellStyle.setBorderTop(BorderStyle.THIN);
      }
    }

    XSSFFont font = (XSSFFont) workbook.createFont();
    font.setFontName("Times New Roman");
    font.setBold(isBold);
    font.setItalic(isItalic);
    cellStyle.setFont(font);
    cellStyle.setVerticalAlignment(VerticalAlignment.CENTER);

    return cellStyle;
  }

  public static XSSFCellStyle createAllBorderCellAndCustomBorder(
      Workbook workbook, @Nullable BorderStyle borderStyle) {
    XSSFCellStyle cellStyle = (XSSFCellStyle) workbook.createCellStyle();
    if (borderStyle != null) {
      cellStyle.setBorderBottom(borderStyle);
      cellStyle.setBorderLeft(borderStyle);
      cellStyle.setBorderRight(borderStyle);
      cellStyle.setBorderTop(borderStyle);
    } else {
      cellStyle.setBorderBottom(BorderStyle.THIN);
      cellStyle.setBorderLeft(BorderStyle.THIN);
      cellStyle.setBorderRight(BorderStyle.THIN);
      cellStyle.setBorderTop(BorderStyle.THIN);
    }
    cellStyle.setWrapText(true);
    cellStyle.setAlignment(HorizontalAlignment.CENTER);
    return cellStyle;
  }

  public static Row customeCreateTitleRowAndCustomBorder(
      Workbook workbook,
      Sheet sheet,
      List<String> columnNames,
      Integer rowIndex,
      Integer cellIndex,
      @Nullable XSSFColor bg,
      @Nullable BorderStyle borderStyle,
      int height) {
    Row headerRow = getRowWithCreate(sheet, rowIndex);

    AtomicInteger idx = new AtomicInteger(cellIndex);

    XSSFCellStyle cellStyle = createAllBorderCellAndCustomBorder(workbook, borderStyle);
    cellStyle.setVerticalAlignment(VerticalAlignment.CENTER);
    cellStyle.setFillBackgroundColor(IndexedColors.OLIVE_GREEN.getIndex());
    cellStyle.setFillForegroundColor(IndexedColors.LAVENDER.index);

    if (bg != null) {
      cellStyle.setFillForegroundColor(bg);
      cellStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
    }

    XSSFFont font = createTimeNewRomanFont(workbook);
    cellStyle.setFont(font);

    columnNames.forEach(
        columnName -> {
          Cell cell = headerRow.createCell(idx.getAndIncrement());
          cell.setCellValue(columnName);
          cell.setCellStyle(cellStyle);
        });
    headerRow.setHeight((short) height);
    return headerRow;
  }

  public static Comparator<Map<String, Object>> compareWithMapString(String key1, String key2) {
    return (e1, e2) -> {
      if (e1.get(key1) != null && e2.get(key2) != null) {
        String value1 = e1.get(key1).toString();
        String value2 = e2.get(key2).toString();
        return value1.compareTo(value2);
      }
      return 0;
    };
  }

  public static void mergeCellsInRow(Sheet sheet, int rowIndex) {
    Row row = sheet.getRow(rowIndex);
    if (row != null) {
      int lastCellNum = row.getLastCellNum();
      for (int i = 0; i < lastCellNum; i++) {
        Cell currentCell = row.getCell(i);
        String currentValue = currentCell.getStringCellValue();
        int mergeEnd = i;

        // Find the end of the range with the same value
        for (int j = i + 1; j < lastCellNum; j++) {
          Cell nextCell = row.getCell(j);
          if (nextCell != null && currentValue.equals(nextCell.getStringCellValue())) {
            mergeEnd = j;
          } else {
            break;
          }
        }

        // Perform merge if necessary
        if (mergeEnd > i) {
          sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, i, mergeEnd));
          i = mergeEnd; // Skip cells that are already merged
        }
      }
    }
  }

  public static void mergeCellsWithPreviousValue(
      XSSFSheet sheet, int startRow, int endRow, int columnIndex, String value) {
    if (startRow < endRow) {
      CellRangeAddress range = new CellRangeAddress(startRow, endRow, columnIndex, columnIndex);
      sheet.addMergedRegion(range);
      ExcelUtils.setAllBorderRegion(sheet, range);
    }

    Row firstRow = sheet.getRow(startRow);
    if (firstRow == null) {
      firstRow = sheet.createRow(startRow);
    }
    Cell firstCell = firstRow.getCell(columnIndex);
    if (firstCell == null) {
      firstCell = firstRow.createCell(columnIndex);
    }
    firstCell.setCellValue(value);
  }

  public static XSSFCellStyle createStyleNumber(Workbook workbook, boolean isFloat, boolean isInt) {
    return createStyleNumber(workbook, isFloat, isInt, false);
  }

  public static XSSFCellStyle createStyleNumber(
      Workbook workbook, boolean isFloat, boolean isInt, boolean isBold) {
    XSSFCellStyle cellStyle = (XSSFCellStyle) workbook.createCellStyle();

    // Set borders
    cellStyle.setBorderBottom(BorderStyle.THIN);
    cellStyle.setBorderLeft(BorderStyle.THIN);
    cellStyle.setBorderRight(BorderStyle.THIN);
    cellStyle.setBorderTop(BorderStyle.THIN);

    // Set data format based on the type (Integer or Float)
    if (isFloat) {
      cellStyle.setDataFormat(workbook.createDataFormat().getFormat("#,##0.0##"));
    } else if (isInt) {
      cellStyle.setDataFormat(workbook.createDataFormat().getFormat("#,##0"));
    } else {
      cellStyle.setDataFormat(workbook.createDataFormat().getFormat(";;"));
    }
    // Set alignment
    cellStyle.setAlignment(HorizontalAlignment.CENTER);
    cellStyle.setVerticalAlignment(VerticalAlignment.CENTER);

    // Set font
    XSSFFont font = (XSSFFont) workbook.createFont();
    font.setFontName("Times New Roman");
    font.setBold(isBold);
    cellStyle.setFont(font);

    // Wrap text
    cellStyle.setWrapText(true);

    return cellStyle;
  }

  public static Row customeCreateTitleRowWithCellStyle(
      Workbook workbook,
      Sheet sheet,
      List<String> columnNames,
      Integer rowIndex,
      Integer cellIndex,
      XSSFCellStyle cellStyle) {
    Row headerRow = getRowWithCreate(sheet, rowIndex);
    AtomicInteger idx = new AtomicInteger(cellIndex);
    columnNames.forEach(
        columnName -> {
          Cell cell = headerRow.createCell(idx.getAndIncrement());
          cell.setCellValue(columnName);
          cell.setCellStyle(cellStyle);
        });

    return headerRow;
  }

  public static void mergeCellsIfSameValue(
      Sheet sheet, int startRow, int endRow, int startCol, int endCol) {
    // Duyệt qua từng dòng
    for (int rowNum = startRow; rowNum <= endRow; rowNum++) {
      Row row = sheet.getRow(rowNum);
      if (row == null) continue; // Bỏ qua nếu dòng trống

      // Duyệt qua từng cặp ô trong phạm vi cột
      for (int col = startCol; col < endCol; col++) {
        Cell cell1 = row.getCell(col);
        Cell cell2 = row.getCell(col + 1);

        if (cell1 != null && cell2 != null) {
          // Kiểm tra giá trị của hai ô
          if (cell1.getCellType() == cell2.getCellType()
              && cell1.getStringCellValue().equals(cell2.getStringCellValue())) {
            CellRangeAddress newRegion = new CellRangeAddress(rowNum, rowNum, col, col + 1);

            // Kiểm tra chồng chéo
            for (int i = 0; i < sheet.getNumMergedRegions(); i++) {
              CellRangeAddress existingRegion = sheet.getMergedRegion(i);
              if (existingRegion.intersects(newRegion)) {
                return; // Bỏ qua nếu chồng chéo
              }
            }
            // Merge các ô nếu giống nhau
            sheet.addMergedRegion(newRegion);
          }
        }
      }
    }
  }

  public static void oneCompareAndMergeMultiCellWithSameValue(
      Sheet sheet, int startRowIdx, int endRowIdx, int colIdx, int[] columnMergeArray) {
    // Merge cell
    int firstRow = startRowIdx;
    int lastRow = startRowIdx;
    for (int rowIdx = startRowIdx; rowIdx <= endRowIdx - 1; rowIdx++) {
      Cell cell = sheet.getRow(rowIdx).getCell(colIdx);
      String currentValue = handleCell(cell);
      String nextRowCellValue;
      if (rowIdx < endRowIdx - 1) {
        if (sheet.getRow(rowIdx + 1) == null) {
          sheet.createRow(rowIdx + 1);
        }
        Cell nextRowCell = sheet.getRow(rowIdx + 1).getCell(colIdx);
        nextRowCellValue = handleCell(nextRowCell);
      } else {
        nextRowCellValue = null;
      }
      boolean isNotSameValue =
          !StringUtils.equals(currentValue, nextRowCellValue)
              || StringUtils.isBlank(currentValue)
              || StringUtils.isBlank(nextRowCellValue);
      if (isNotSameValue) {
        if (firstRow != lastRow) {
          for (int i : columnMergeArray) {
            CellRangeAddress cellRangeAddress = new CellRangeAddress(firstRow, lastRow, i, i);
            sheet.addMergedRegion(cellRangeAddress);
          }
        }
        firstRow = rowIdx + 1;
      }
      // always update last row
      lastRow = rowIdx + 1;
    }
  }

  public static void mergeCellsInRowWithBeforeRow(
      Sheet sheet, int rowIndex, int startCol, int endCol) {
    Row row = sheet.getRow(rowIndex);
    Row beforeRow = sheet.getRow(rowIndex - 1);
    if (row != null) {
      for (int i = startCol; i <= endCol; i++) {
        Cell currentCell = row.getCell(i);
        Cell currentBeforeCell = beforeRow.getCell(i);
        String currentValue = currentCell.getStringCellValue();
        String currentBeforeValue = currentBeforeCell.getStringCellValue();
        int mergeEnd = i;

        // Find the end of the range with the same value
        for (int j = i + 1; j <= endCol; j++) {
          Cell nextCell = row.getCell(j);
          Cell nextBeforeCell = beforeRow.getCell(j);
          if ((nextCell != null && currentValue.equals(nextCell.getStringCellValue()))
              && (nextBeforeCell != null
                  && currentBeforeValue.equals(nextBeforeCell.getStringCellValue()))) {
            mergeEnd = j;
          } else {
            break;
          }
        }

        // Perform merge if necessary
        if (mergeEnd > i) {
          sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, i, mergeEnd));
          i = mergeEnd; // Skip cells that are already merged
        }
      }
    }
  }

  public static void mergeCellsInRowWithStartAndEndCol(
      Sheet sheet, int rowIndex, int startCol, int endCol) {
    Row row = sheet.getRow(rowIndex);
    if (row != null) {
      for (int i = startCol; i <= endCol; i++) {
        Cell currentCell = row.getCell(i);
        String currentValue = currentCell.getStringCellValue();
        int mergeEnd = i;

        // Find the end of the range with the same value
        for (int j = i + 1; j <= endCol; j++) {
          Cell nextCell = row.getCell(j);
          if (nextCell != null && currentValue.equals(nextCell.getStringCellValue())) {
            mergeEnd = j;
          } else {
            break;
          }
        }

        // Perform merge if necessary
        if (mergeEnd > i) {
          sheet.addMergedRegion(new CellRangeAddress(rowIndex, rowIndex, i, mergeEnd));
          i = mergeEnd; // Skip cells that are already merged
        }
      }
    }
  }

  public static void setRegionStyle(Sheet sheet, CellRangeAddress region, CellStyle style) {
    for (int rowIndex = region.getFirstRow(); rowIndex <= region.getLastRow(); rowIndex++) {
      Row row = sheet.getRow(rowIndex) != null ? sheet.getRow(rowIndex) : sheet.createRow(rowIndex);
      for (int colIndex = region.getFirstColumn(); colIndex <= region.getLastColumn(); colIndex++) {
        Cell cell =
            row.getCell(colIndex) != null ? row.getCell(colIndex) : row.createCell(colIndex);
        cell.setCellStyle(style);
      }
    }
  }

  public static void autoSizeColumns(Sheet sheet, int totalColumns, int defaultWidth) {
    for (int i = 0; i < totalColumns; i++) {
      sheet.autoSizeColumn(i);
      int width = sheet.getColumnWidth(i);
      if (width < defaultWidth) {
        sheet.setColumnWidth(i, defaultWidth);
      }
    }
  }

  /** Merge vùng cell và áp dụng style cho vùng đó. */
  public static void mergeAndStyleRegion(
      Sheet sheet,
      int firstRow,
      int lastRow,
      int firstCol,
      int lastCol,
      CellStyle style,
      boolean doMerge) {
    CellRangeAddress region = new CellRangeAddress(firstRow, lastRow, firstCol, lastCol);
    if (doMerge) sheet.addMergedRegion(region);
    setRegionStyle(sheet, region, style);
  }

  /** Merge vùng cell và áp dụng style cho vùng đó. */
  public static void mergeAndStyleRegion(
      Sheet sheet, int firstRow, int lastRow, int firstCol, int lastCol, CellStyle style) {
    mergeAndStyleRegion(sheet, firstRow, lastRow, firstCol, lastCol, style, Boolean.TRUE);
  }

  public static void mergeCellsByGroup(
      Sheet sheet,
      List<Map<String, Object>> list,
      int startRow,
      String groupKey,
      int[] mergeColumns) {
    // Kiểm tra xem mảng mergeColumns có chứa cột 0 (stt) hay không
    boolean mergeStt = false;
    for (int col : mergeColumns) {
      if (col == 0) {
        mergeStt = true;
        break;
      }
    }

    // Nhóm các dòng theo giá trị của groupKey (sử dụng LinkedHashMap để giữ thứ tự)
    LinkedHashMap<Object, List<Integer>> groupRows = new LinkedHashMap<>();
    for (int i = 0; i < list.size(); i++) {
      Map<String, Object> record = list.get(i);
      Object keyValue = record.get(groupKey);
      int rowIndex = startRow + i;
      groupRows.computeIfAbsent(keyValue, _ -> new ArrayList<>()).add(rowIndex);
    }

    Workbook workbook = sheet.getWorkbook();
    // Tạo cell style cho cột stt: căn giữa và border
    CellStyle sttStyle = workbook.createCellStyle();
    sttStyle.setAlignment(HorizontalAlignment.CENTER);
    sttStyle.setVerticalAlignment(VerticalAlignment.CENTER);
    sttStyle.setBorderTop(BorderStyle.THIN);
    sttStyle.setBorderBottom(BorderStyle.THIN);
    sttStyle.setBorderLeft(BorderStyle.THIN);
    sttStyle.setBorderRight(BorderStyle.THIN);

    int sttCounter = 1;

    // Nếu cột stt không cần merge (mergeColumns không chứa 0) -> gán stt cho mỗi dòng
    if (!mergeStt) {
      for (int i = 0; i < list.size(); i++) {
        int rowIndex = startRow + i;
        Row row = sheet.getRow(rowIndex);
        if (row == null) {
          row = sheet.createRow(rowIndex);
        }
        Cell cell = row.getCell(0);
        if (cell == null) {
          cell = row.createCell(0);
        }
        cell.setCellValue(sttCounter++);
        cell.setCellStyle(sttStyle);
      }
    }

    // Xử lý merge cho các nhóm (với các cột nằm trong mergeColumns)
    for (Map.Entry<Object, List<Integer>> entry : groupRows.entrySet()) {
      List<Integer> rows = entry.getValue();
      int firstRow = rows.getFirst();
      int lastRow = rows.getLast();

      for (int col : mergeColumns) {
        // Nếu đây là cột stt và ta cần merge thì chỉ gán stt cho ô ở dòng đầu tiên của nhóm
        if (col == 0 && mergeStt) {
          Row row = sheet.getRow(firstRow);
          if (row == null) {
            row = sheet.createRow(firstRow);
          }
          Cell cell = row.getCell(0);
          if (cell == null) {
            cell = row.createCell(0);
          }
          cell.setCellValue(sttCounter++);
          cell.setCellStyle(sttStyle);
        }

        // Nếu nhóm có nhiều hơn 1 dòng -> thực hiện merge vùng cho cột hiện tại
        if (rows.size() > 1) {
          CellRangeAddress region = new CellRangeAddress(firstRow, lastRow, col, col);
          sheet.addMergedRegion(region);
          // Đảm bảo vùng merge giữ lại border
          RegionUtil.setBorderTop(BorderStyle.THIN, region, sheet);
          RegionUtil.setBorderBottom(BorderStyle.THIN, region, sheet);
          RegionUtil.setBorderLeft(BorderStyle.THIN, region, sheet);
          RegionUtil.setBorderRight(BorderStyle.THIN, region, sheet);
        }
      }
      // Với nhóm chỉ có 1 dòng và nếu mergeStt == true, thì đã gán stt ở trên và không cần merge
    }
  }

  /**
   * Merge theo chiều dọc các cell có cùng giá trị composite của trường cha và trường con.
   *
   * @param sheet Worksheet chứa dữ liệu đã được ghi.
   * @param list Danh sách dữ liệu (mỗi Map tương ứng với một row trong sheet).
   * @param startRow Dòng bắt đầu chứa dữ liệu (ví dụ: nếu header ở dòng 1 thì dữ liệu bắt đầu từ
   *     dòng 2).
   * @param parentKey Tên trường cha (ví dụ: "idCoSo").
   * @param childKey Tên trường con cần merge (ví dụ: "idChungNhan" hoặc "idChiTiet").
   * @param mergeColumns Mảng các chỉ số cột cần thực hiện merge cho nhóm đó.
   */
  public static void mergeCellsByCompositeGroup(
      Sheet sheet,
      List<Map<String, Object>> list,
      int startRow,
      String parentKey,
      String childKey,
      int[] mergeColumns) {
    // Sử dụng LinkedHashMap để giữ đúng thứ tự theo thứ tự chèn
    LinkedHashMap<String, List<Integer>> groupRows = new LinkedHashMap<>();

    for (int i = 0; i < list.size(); i++) {
      Map<String, Object> record = list.get(i);
      Object parentVal = record.get(parentKey);
      Object childVal = record.get(childKey);
      // Tạo composite key bằng cách kết hợp parent và child (có thể xử lý null nếu cần)
      String compositeKey =
          (parentVal != null ? parentVal.toString() : "null")
              + "_"
              + (childVal != null ? childVal.toString() : "null");
      int rowIndex = startRow + i;
      groupRows.computeIfAbsent(compositeKey, _ -> new ArrayList<>()).add(rowIndex);
    }

    // Với mỗi nhóm có nhiều hơn 1 dòng, thực hiện merge các cột trong mergeColumns
    for (Map.Entry<String, List<Integer>> entry : groupRows.entrySet()) {
      List<Integer> rows = entry.getValue();
      if (rows.size() > 1) {
        int firstRow = rows.getFirst();
        int lastRow = rows.getLast();
        for (int col : mergeColumns) {
          CellRangeAddress region = new CellRangeAddress(firstRow, lastRow, col, col);
          sheet.addMergedRegion(region);
        }
      }
    }
  }

  public static Map<String, XSSFCellStyle> createStylesCustomWithText(
      XSSFWorkbook wb, boolean isBold) {
    Map<String, XSSFCellStyle> styles = new HashMap<>();
    styles.put("header", createStyle(wb, isBold, true));
    styles.put("textLeft", createStyle(wb, isBold, true));
    styles.put("center", createStyle(wb, isBold, true, false, 2));
    styles.put("textRight", createStyle(wb, isBold, true, false, 3));
    styles.put("float", createStyleNumber(wb, true, false, isBold));
    styles.put("int", createStyleNumber(wb, false, true, isBold));
    styles.put("blank", createStyleNumber(wb, false, false, isBold));
    styles.put("numNormal", createStyleNumber(wb, false, false, isBold));
    return styles;
  }

  public static void setCellValueCustom(Cell cell, Object value, XSSFCellStyle style) {
    cell.setCellStyle(style);
    if (value instanceof Number) {
      cell.setCellValue(((Number) value).doubleValue());
    } else if (value != null) {
      cell.setCellValue(value.toString());
    }
  }

  public static void applyCellStyle(Cell cell, Map<String, XSSFCellStyle> styles) {
    if (cell != null && cell.getCellType() == CellType.NUMERIC) {
      double cellValue = cell.getNumericCellValue();
      if (cellValue == 0) {
        cell.setCellStyle(styles.get("blank"));
      } else if (cellValue == (int) cellValue) {
        cell.setCellStyle(styles.get("int"));
      } else {
        cell.setCellStyle(styles.get("float"));
      }
    } else {
      assert cell != null;
      cell.setCellStyle(styles.get("textLeft"));
    }
  }
}
