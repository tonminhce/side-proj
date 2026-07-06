package vn.vnpt.util.common.excel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFName;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import vn.vnpt.util.common.TemplateExcelWriter;
import vn.vnpt.util.exception.CustomException;

class ExcelImportExportHelperTest {

  @Test
  void validateExcelFileRejectsNonExcelExtension() {
    MockMultipartFile file =
        new MockMultipartFile("file", "data.txt", "text/plain", "abc".getBytes());

    CustomException exception =
        assertThrows(
            CustomException.class,
            () ->
                ExcelImportExportHelper.validateExcelFile(
                    file,
                    1024,
                    "File excel không được để trống",
                    "File excel vượt quá dung lượng cho phép",
                    "File excel không đúng định dạng"));

    assertEquals("File excel không đúng định dạng", exception.getMessage());
  }

  @Test
  void mergeHorizontalMergesCellsOnSameRow() throws Exception {
    try (Workbook workbook = new XSSFWorkbook()) {
      Sheet sheet = workbook.createSheet("Data");

      ExcelImportExportHelper.mergeHorizontal(sheet, 2, 1, 3);

      assertEquals(1, sheet.getNumMergedRegions());
      CellRangeAddress region = sheet.getMergedRegion(0);
      assertEquals(2, region.getFirstRow());
      assertEquals(2, region.getLastRow());
      assertEquals(1, region.getFirstColumn());
      assertEquals(3, region.getLastColumn());
    }
  }

  @Test
  void mergeVerticalMergesCellsOnSameColumn() throws Exception {
    try (Workbook workbook = new XSSFWorkbook()) {
      Sheet sheet = workbook.createSheet("Data");

      ExcelImportExportHelper.mergeVertical(sheet, 1, 4, 2);

      assertEquals(1, sheet.getNumMergedRegions());
      CellRangeAddress region = sheet.getMergedRegion(0);
      assertEquals(1, region.getFirstRow());
      assertEquals(4, region.getLastRow());
      assertEquals(2, region.getFirstColumn());
      assertEquals(2, region.getLastColumn());
    }
  }

  @Test
  void createHeaderWritesValueAtRowAndColumn() throws Exception {
    try (Workbook workbook = new XSSFWorkbook()) {
      Sheet sheet = workbook.createSheet("Data");
      CellStyle style = workbook.createCellStyle();

      Cell cell = ExcelImportExportHelper.createHeader(sheet, 1, 2, "Mã tham số", style);

      assertEquals(1, cell.getRowIndex());
      assertEquals(2, cell.getColumnIndex());
      assertEquals("Mã tham số", cell.getStringCellValue());
      assertEquals(style.getIndex(), cell.getCellStyle().getIndex());
      assertEquals(0, sheet.getNumMergedRegions());
    }
  }

  @Test
  void createHeaderCanMergeFromStartToEndRegion() throws Exception {
    try (Workbook workbook = new XSSFWorkbook()) {
      Sheet sheet = workbook.createSheet("Data");
      CellStyle style = workbook.createCellStyle();

      Cell cell =
          ExcelImportExportHelper.createHeader(sheet, 0, 1, "Thông tin chung", style, 0, 0, 1, 4);

      assertEquals("Thông tin chung", cell.getStringCellValue());
      assertEquals(style.getIndex(), cell.getCellStyle().getIndex());
      assertEquals(1, sheet.getNumMergedRegions());
      CellRangeAddress region = sheet.getMergedRegion(0);
      assertEquals(0, region.getFirstRow());
      assertEquals(0, region.getLastRow());
      assertEquals(1, region.getFirstColumn());
      assertEquals(4, region.getLastColumn());
    }
  }

  @Test
  void createCellWritesValueAtRowAndColumn() throws Exception {
    try (Workbook workbook = new XSSFWorkbook()) {
      Sheet sheet = workbook.createSheet("Data");
      CellStyle style = workbook.createCellStyle();

      Cell cell = ExcelImportExportHelper.createCell(sheet, 2, 3, 15, style);

      assertEquals(2, cell.getRowIndex());
      assertEquals(3, cell.getColumnIndex());
      assertEquals(15D, cell.getNumericCellValue());
      assertEquals(style.getIndex(), cell.getCellStyle().getIndex());
    }
  }

  @Test
  void createRowCellsWritesValuesFromStartColumn() throws Exception {
    try (Workbook workbook = new XSSFWorkbook()) {
      Sheet sheet = workbook.createSheet("Data");
      CellStyle style = workbook.createCellStyle();

      Row row =
          ExcelImportExportHelper.createRowCells(
              sheet, 4, 1, List.of("A001", "Tên A", true), style);

      assertEquals(4, row.getRowNum());
      assertEquals("A001", row.getCell(1).getStringCellValue());
      assertEquals("Tên A", row.getCell(2).getStringCellValue());
      assertEquals(true, row.getCell(3).getBooleanCellValue());
      assertEquals(style.getIndex(), row.getCell(1).getCellStyle().getIndex());
      assertEquals(style.getIndex(), row.getCell(2).getCellStyle().getIndex());
      assertEquals(style.getIndex(), row.getCell(3).getCellStyle().getIndex());
    }
  }

  @Test
  void applyStyleToRegionStylesEveryCellInRange() throws Exception {
    try (Workbook workbook = new XSSFWorkbook()) {
      Sheet sheet = workbook.createSheet("Data");
      CellStyle style = workbook.createCellStyle();

      ExcelImportExportHelper.applyStyleToRegion(sheet, 1, 2, 1, 2, style);

      assertEquals(style.getIndex(), sheet.getRow(1).getCell(1).getCellStyle().getIndex());
      assertEquals(style.getIndex(), sheet.getRow(1).getCell(2).getCellStyle().getIndex());
      assertEquals(style.getIndex(), sheet.getRow(2).getCell(1).getCellStyle().getIndex());
      assertEquals(style.getIndex(), sheet.getRow(2).getCell(2).getCellStyle().getIndex());
    }
  }

  @Test
  void autoSizeColumnsRejectsInvalidColumnRange() throws Exception {
    try (Workbook workbook = new XSSFWorkbook()) {
      Sheet sheet = workbook.createSheet("Data");

      CustomException exception =
          assertThrows(
              CustomException.class, () -> ExcelImportExportHelper.autoSizeColumns(sheet, 2, 1));

      assertEquals("Cột bắt đầu không được lớn hơn cột kết thúc", exception.getMessage());
    }
  }

  @Test
  void writeTemplateDelegatesToSingleDataTemplateWriter() {
    StubTemplateExcelWriter writer = new StubTemplateExcelWriter();
    Object data = new Object();
    List<Integer> removeColumns = List.of(1, 3);
    List<Integer> hideColumns = List.of(2);

    File result =
        ExcelImportExportHelper.writeSingleTemplate(
            writer, data, "Sheet A", "Tiêu đề A", removeColumns, hideColumns);

    assertSame(writer.file, result);
    assertSame(data, writer.data);
    assertEquals("Sheet A", writer.dynamicSheetName);
    assertEquals("Tiêu đề A", writer.dynamicTitle);
    assertEquals(removeColumns, writer.columnsToRemove);
    assertEquals(hideColumns, writer.columnsToHide);
  }

  @Test
  void writeMultiSheetTemplateDelegatesToMultiSheetWriter() {
    StubTemplateExcelWriter writer = new StubTemplateExcelWriter();
    Object data = new Object();
    List<Integer> columnsToHide = List.of(0, 4);
    List<Integer> removeColumns = List.of(5);

    File result =
        ExcelImportExportHelper.writeMultiSheetTemplate(
            writer, data, columnsToHide, "Tiêu đề nhiều sheet", removeColumns);

    assertSame(writer.file, result);
    assertSame(data, writer.data);
    assertEquals(columnsToHide, writer.columnsToHide);
    assertEquals("Tiêu đề nhiều sheet", writer.dynamicTitle);
    assertEquals(removeColumns, writer.columnsToRemove);
  }

  @Test
  void writeTemplateSheetDelegatesToTemplateSheetWriter() {
    StubTemplateExcelWriter writer = new StubTemplateExcelWriter();
    Object data = new Object();

    File result = ExcelImportExportHelper.writeTemplateSheet(writer, data);

    assertSame(writer.file, result);
    assertSame(data, writer.data);
  }

  @Test
  void setXssfListValidationAddsDropdownToRequestedRange() throws Exception {
    try (XSSFWorkbook workbook = new XSSFWorkbook()) {
      XSSFSheet sheet = workbook.createSheet("Import");

      ExcelImportExportHelper.setXssfListValidation(sheet, "Ref!$B$1:$B$2", 1, 5, 2, 2);

      List<? extends DataValidation> validations = sheet.getDataValidations();
      assertEquals(1, validations.size());
      CellRangeAddressList regions = validations.getFirst().getRegions();
      assertEquals(1, regions.getCellRangeAddress(0).getFirstRow());
      assertEquals(5, regions.getCellRangeAddress(0).getLastRow());
      assertEquals(2, regions.getCellRangeAddress(0).getFirstColumn());
      assertEquals(2, regions.getCellRangeAddress(0).getLastColumn());
    }
  }

  @Test
  void createCategorySheetWritesCategoryDataHidesSourceSheetAndAddsDropdown() throws Exception {
    try (XSSFWorkbook workbook = new XSSFWorkbook()) {
      XSSFSheet sourceSheet = workbook.createSheet("RefCategory");
      XSSFSheet destinationSheet = workbook.createSheet("Import");
      List<Map<String, Object>> categories =
          List.of(
              Map.of("code", "A001", "name", "Phân bón"),
              Map.of("code", "A002", "name", "Thuốc BVTV"));

      ExcelImportExportHelper.createCategorySheet(
          workbook,
          sourceSheet,
          destinationSheet,
          categories,
          List.of("code", "name"),
          1,
          10,
          3,
          3,
          "123");

      assertEquals("A001", sourceSheet.getRow(0).getCell(0).getStringCellValue());
      assertEquals("Phân bón", sourceSheet.getRow(0).getCell(1).getStringCellValue());
      assertTrue(workbook.isSheetHidden(workbook.getSheetIndex(sourceSheet)));
      assertEquals(1, destinationSheet.getDataValidations().size());
    }
  }

  @Test
  void renderStandardRefDropdownCreatesHiddenRefSheetNamedRangeAndValidation() throws Exception {
    try (XSSFWorkbook workbook = new XSSFWorkbook()) {
      XSSFSheet targetSheet = workbook.createSheet("Import");
      List<Map<String, Object>> rows =
          List.of(Map.of("uuid", 1L, "name", "Lúa"), Map.of("uuid", 2L, "name", "Ngô"));

      ExcelImportExportHelper.renderStandardRefDropdown(
          workbook, targetSheet, "Ref_crop", "crop", rows, "uuid", "name", 2, 1, 20);

      XSSFSheet refSheet = workbook.getSheet("Ref_crop");
      XSSFName name =
          workbook.getAllNames().stream()
              .filter(item -> "crop".equals(item.getNameName()))
              .findFirst()
              .orElseThrow();
      assertEquals("value", refSheet.getRow(0).getCell(0).getStringCellValue());
      assertEquals("label", refSheet.getRow(0).getCell(1).getStringCellValue());
      assertEquals("1", refSheet.getRow(1).getCell(0).getStringCellValue());
      assertEquals("Lúa", refSheet.getRow(1).getCell(1).getStringCellValue());
      assertTrue(workbook.isSheetHidden(workbook.getSheetIndex(refSheet)));
      assertEquals("'Ref_crop'!$B$2:$B$3", name.getRefersToFormula());
      assertEquals(1, targetSheet.getDataValidations().size());
    }
  }

  private static final class StubTemplateExcelWriter extends TemplateExcelWriter {
    private final File file = new File("template.xlsx");
    private Object data;
    private String dynamicSheetName;
    private String dynamicTitle;
    private List<Integer> columnsToRemove;
    private List<Integer> columnsToHide;

    @Override
    public <T> File setSingleDataToTemplate(
        T data,
        String dynamicSheetName,
        String dynamicTitle,
        List<Integer> columnsToRemove,
        List<Integer> columnsToHide) {
      this.data = data;
      this.dynamicSheetName = dynamicSheetName;
      this.dynamicTitle = dynamicTitle;
      this.columnsToRemove = columnsToRemove;
      this.columnsToHide = columnsToHide;
      return file;
    }

    @Override
    public <T> File setSingleDataToTemplateMultiSheet(
        T data, List<Integer> columnsToHide, String dynamicTitle, List<Integer> removeCols) {
      this.data = data;
      this.columnsToHide = columnsToHide;
      this.dynamicTitle = dynamicTitle;
      this.columnsToRemove = removeCols;
      return file;
    }

    @Override
    public <T> File setSingleDataToTemplateSheet(T data) {
      this.data = data;
      return file;
    }
  }
}
