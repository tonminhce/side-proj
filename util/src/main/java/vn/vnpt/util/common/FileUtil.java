package vn.vnpt.util.common;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.io.Files;
import com.lowagie.text.pdf.BaseFont;
import io.minio.*;
import io.minio.errors.MinioException;
import jakarta.servlet.http.HttpServletResponse;
import java.io.*;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.poifs.crypt.HashAlgorithm;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFFormulaEvaluator;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.FileCopyUtils;
import org.springframework.web.multipart.MultipartFile;
import org.xhtmlrenderer.pdf.ITextRenderer;
import org.xhtmlrenderer.resource.FSEntityResolver;
import org.xml.sax.SAXException;
import vn.vnpt.util.annotation.ExcelExportForm;
import vn.vnpt.util.annotation.ExcelExportGeneralConfig;
import vn.vnpt.util.annotation.ExcelExportParams;
import vn.vnpt.util.annotation.ValueDefinition;
import vn.vnpt.util.common.constant.CommonConstant;
import vn.vnpt.util.exception.CustomException;
import vn.vnpt.util.exception.UpdateException;

@Slf4j
// @Component
public class FileUtil {
  private static final Logger logger = LoggerFactory.getLogger(FileUtil.class);

  @Value("${folder.temp.timeToLive}")
  private int tempFileTTL;

  @Value("${excel.sheet.password}")
  private String excelSheetPassword;

  @Value("${excel.sheet.error}")
  private String excelSheetError;

  private static File TempFolder;

  private File templateExcelFolder;

  private final Random random = new Random();

  public static final int EXCEL_CELL_STRING_MAX_SIZE = 32767;

  /** # DATA TOO LONG ! */
  private static final String DATA_TOO_LONG = "# DATA TOO LONG !";

  private static String endPoint;
  private static String accessKey;
  private static String secretKey;
  private static String bucketName;
  private static String publicBucketName;
  private static String dvcBucketName;
  private static String subDirectory;

  public FileUtil(
      String folderTemp,
      String templateExcel,
      String endPoint,
      String accessKey,
      String secretKey,
      String bucketName,
      String publicBucketName,
      String dvcBucketName,
      String subDirectory) {
    FileUtil.endPoint = endPoint;
    FileUtil.accessKey = accessKey;
    FileUtil.secretKey = secretKey;
    FileUtil.bucketName = bucketName;
    FileUtil.publicBucketName = publicBucketName;
    FileUtil.dvcBucketName = dvcBucketName;
    FileUtil.subDirectory = subDirectory;
    try {
      log.info(
          "+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++");
      log.info(
          "++++++++++++++++++++++++++++++          FILE UTIL          ++++++++++++++++++++++++++++++++");
      log.info(
          "+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++");
      log.info("FileUtil starting...");
      templateExcelFolder = new File(templateExcel);
      if (!templateExcelFolder.isDirectory()) return;

      log.info("folder.temp hihi : {}", folderTemp);
      log.info("endPoint : {}", endPoint);
      TempFolder = new File(folderTemp);
      if (!TempFolder.exists() || !TempFolder.isDirectory()) {
        log.info("TempFolder is not existed. Creating..");
        boolean result = TempFolder.mkdir();
        log.info("Result : {}", result);
        if (result) {
          log.info("canRead : {}", TempFolder.canRead());
          log.info("canWrite : {}", TempFolder.canWrite());
          log.info("canExecute : {}", TempFolder.canExecute());
        }
      } else {
        log.info("TempFolder is existed.");
        log.info("canRead : {}", TempFolder.canRead());
        log.info("canWrite : {}", TempFolder.canWrite());
        log.info("canExecute : {}", TempFolder.canExecute());
      }

      log.info(
          "+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++");
      log.info(
          "+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++");

    } catch (Exception e) {
      log.error("FileUtil init has failed.", e);
    }
  }

  public File getTempFile(String fileName) {
    if (StringUtils.isBlank(fileName)) return null;
    File file = new File(TempFolder, fileName);
    if (file.exists()) return file;
    return null;
  }

  public enum Extension {
    PDF("pdf"),
    PNG("png"),
    ZIP("zip"),
    XLSX("xlsx"),
    XLS("xls"),
    XLSM("xlsm"),
    UNKNOWN("");

    private final String value;

    Extension(String value) {
      this.value = value;
    }

    public static Extension get(String extension) {
      if (StringUtils.isBlank(extension)) return Extension.UNKNOWN;
      for (Extension ext : Extension.values())
        if (ext.value.equalsIgnoreCase(extension)) return ext;
      return Extension.UNKNOWN;
    }

    /** In case extension can not be found, will return Extension */
    public static Extension getFromName(String name) {
      return get(getExtensionOnly(name));
    }
  }

  private static String getNameOnly(String name) {
    if (StringUtils.isBlank(name)) return "";
    int lastDotIndex = name.lastIndexOf(".");
    return lastDotIndex == -1 ? name : name.substring(0, lastDotIndex);
  }

  private static String getExtensionOnly(String name) {
    if (StringUtils.isBlank(name)) return "";
    int lastDotIndex = name.lastIndexOf(".");
    return lastDotIndex == -1 ? name : name.substring(lastDotIndex + 1);
  }

  enum IsUnique {
    TRUE,
    FALSE,
    UNKNOWN
  }

  private static final String DATA_DIVIDER = "$$";
  private static final String DATA_DIVIDER_REGEX = "\\$\\$";
  private static final String DATA_REGEX = DATA_DIVIDER_REGEX + "(.+?)" + DATA_DIVIDER_REGEX;
  private static final Pattern DATA_PATTERN = Pattern.compile(DATA_REGEX);

  private IsUnique isUniqueFormatted(String name) {
    String nameOnly = getNameOnly(name);
    Matcher dataMatcher = DATA_PATTERN.matcher(nameOnly);
    if (dataMatcher.find()) return IsUnique.TRUE;
    else return IsUnique.FALSE;
  }

  private String genUniqueName(String name) {
    String result;
    if (StringUtils.isBlank(name)) return null;

    String nameOnly = getNameOnly(name);
    String extension = getExtensionOnly(name);

    int randNum = 100000 + random.nextInt(999999);
    result =
        DATA_DIVIDER
            + nameOnly
            + DATA_DIVIDER
            + System.currentTimeMillis()
            + randNum
            + (extension.isEmpty() ? "" : "." + extension);
    return result;
  }

  private String revertUniqueName(String name) {
    String revertedName = "";
    if (StringUtils.isBlank(name)) return revertedName;

    String nameOnly = getNameOnly(name);
    String extension = getExtensionOnly(name);

    if (nameOnly.isEmpty()) return revertedName;

    Matcher dataMatcher = DATA_PATTERN.matcher(nameOnly);
    if (dataMatcher.find()) revertedName = dataMatcher.group(1);

    return revertedName + (extension.isEmpty() ? "" : "." + extension);
  }

  private static String addIndexToFileName(String name, int index) {
    if (StringUtils.isBlank(name)) return "";

    String nameOnly = getNameOnly(name);
    String extension = getExtensionOnly(name);

    return nameOnly + "_" + index + (extension.isEmpty() ? "" : "." + extension);
  }

  private String changeExtension(String name, Extension newExt) {
    if (StringUtils.isBlank(name)) return "";

    return getNameOnly(name) + (newExt != null ? ("." + newExt.value) : "");
  }

  public static class TempFile extends File {
    @Serial private static final long serialVersionUID = -2780778674496516447L;
    @Getter private final String businessName;
    @Getter private final Extension extension;

    /**
     * @param systemName system unique name
     * @param businessName display name
     */
    private TempFile(String systemName, String businessName, Extension extension) {
      super(TempFolder, systemName);
      this.businessName = businessName;
      this.extension = extension;
    }

    public String indexBusinessName(int index) {
      return FileUtil.addIndexToFileName(this.businessName, index);
    }
  }

  public static class ExcelPosition {
    private int row = 0;
    private int column = 0;

    /**
     * Example:<br>
     * ExcelPosition("A1")<br>
     * ExcelPosition("AH20")<br>
     *
     * @param cellName The name of the cell
     */
    public ExcelPosition(String cellName) {
      String[] part = cellName.split("(?<=\\D)(?=\\d)");
      if (part.length != 2) {
        log.error("Cell name invalid.[" + cellName + "]");
        return;
      }
      this.row = Integer.parseInt(part[1]) - 1;
      this.column = CellReference.convertColStringToIndex(part[0]);
    }

    /**
     * Example:<br>
     * ExcelPosition("A", 1)<br>
     * ExcelPosition("AH", 20)<br>
     *
     * @param columnName Name of the column
     * @param row The number index of the row
     */
    public ExcelPosition(String columnName, int row) {
      this.column = CellReference.convertColStringToIndex(columnName);
      this.row = row;
    }

    private ExcelPosition(int column, int row) {
      this.column = column;
      this.row = row;
    }

    public void nextRow() {
      stepRow(1);
    }

    public void stepRow(int step) {
      this.row += step;
    }

    public void nextColumn() {
      stepColumn(1);
    }

    public void stepColumn(int step) {
      this.column += step;
    }

    public ExcelPosition offset(int offsetColumn, int offsetRow) {
      return new ExcelPosition(this.column + offsetColumn, this.row + offsetRow);
    }

    public String getCellName() {
      return CellReference.convertNumToColString(this.column) + (this.row + 1);
    }

    public static Map<Integer, List<Integer>> deduce(String fromPos, String toPos) {
      return deduce(new ExcelPosition(fromPos), new ExcelPosition(toPos));
    }

    public static Map<Integer, List<Integer>> deduce(ExcelPosition fromPos, ExcelPosition toPos) {
      Map<Integer, List<Integer>> result = new HashMap<>();

      int fromColumn = fromPos.column;
      int toColumn = toPos.column;
      int columnSize = toColumn - fromColumn + 1;

      int fromRow = fromPos.row;
      int toRow = toPos.row;
      int rowSize = toRow - fromRow + 1;

      for (int y = 0; y < rowSize; y++) {
        int rowNum = fromRow + y;
        List<Integer> rowList = new ArrayList<>();
        result.put(rowNum, rowList);
        for (int x = 0; x < columnSize; x++) {
          rowList.add(fromColumn + x);
        }
      }
      return result;
    }
  }

  public boolean isExcelFileName(String name, Extension targetExt) {
    Extension ext = Extension.getFromName(name);
    if (targetExt != null) {
      return targetExt == ext;
    } else {
      return switch (ext) {
        case XLS, XLSX, XLSM -> true;
        default -> false;
      };
    }
  }

  public TempFile multipartToFile(MultipartFile multipartFile) {
    String systemName = System.currentTimeMillis() + "_" + (100000 + random.nextInt(999999));
    TempFile tmpFile =
        new TempFile(systemName, multipartFile.getOriginalFilename(), Extension.UNKNOWN);

    try (FileOutputStream fos = new FileOutputStream(tmpFile)) {
      fos.write(multipartFile.getBytes());
    } catch (Exception e) {
      log.error("TempFile generation has failed.", e);
    }
    return tmpFile;
  }

  private TempFile createTempFile(String name, Extension extension) {
    TempFile file = null;
    try {
      name = StringUtils.isBlank(name) ? "tmp." + extension.value : name;
      file = new TempFile(genUniqueName(name), name, extension);
      if (file.createNewFile()) {
        log.info("TempFile created.[{}]", file.getAbsolutePath());
      } else {
        log.error("TempFile creation has failed.[{}]", file.getAbsolutePath());
      }
    } catch (Exception e) {
      log.error("", e);
    }
    return file;
  }

  public TempFile createZipFile(String name) {
    return createTempFile(name, Extension.ZIP);
  }

  public TempFile createPdfFile(String name) {
    return createTempFile(name, Extension.PDF);
  }

  public TempFile createExcelFile(String name) {
    return createTempFile(name, Extension.XLSX);
  }

  public TempFile createCopy(File sourceFile) {
    try {
      String sourceFileName = sourceFile.getName();
      TempFile copy = createTempFile(sourceFileName, Extension.getFromName(sourceFileName));
      Files.copy(sourceFile, copy);
      return copy;
    } catch (Exception e) {
      log.error("createCopy has failed.", e);
      return null;
    }
  }

  public TempFile createCopy(TempFile sourceFile) {
    try {
      String sourceFileName = sourceFile.getBusinessName();
      TempFile copy = createTempFile(sourceFileName, Extension.getFromName(sourceFileName));
      Files.copy(sourceFile, copy);
      return copy;
    } catch (Exception e) {
      log.error("createCopy has failed.", e);
      return null;
    }
  }

  public XSSFWorkbook getWorkbook(TempFile file) {
    try (FileInputStream fis = new FileInputStream(file)) {
      return new XSSFWorkbook(fis);
    } catch (Exception e) {
      log.error("", e);
    }
    return null;
  }

  public <T> TempFile exportExcelToFile(T dto, boolean recalculateAfter, Class<?>... groups) {

    Set<Class<?>> groupSet = new HashSet<>();
    for (Class<?> group : groups) if (group != null) groupSet.add(group);

    ExcelExportGeneralConfig[] configs =
        dto.getClass().getAnnotationsByType(ExcelExportGeneralConfig.class);
    ExcelExportGeneralConfig config = null;
    if (configs.length == 1) {
      config = configs[0];
    } else if (configs.length > 1) {
      configBreak:
      for (ExcelExportGeneralConfig e : configs) {
        for (Class<?> clazz : e.groups()) {
          if (groupSet.contains(clazz)) {
            config = e;
            break configBreak;
          }
        }
      }
    }
    if (config == null || StringUtils.isBlank(config.exportName())) {
      log.error("exportToExcelTemplate : ExcelExportGeneralConfig invalid.");
      return null;
    }

    HashMap<String, String> paramMap = null;
    try {
      for (Field field : dto.getClass().getDeclaredFields()) {
        field.setAccessible(true);
        if (field.isAnnotationPresent(ExcelExportParams.class)
            && field.getType().isAssignableFrom(Map.class)) {
          paramMap = (HashMap<String, String>) field.get(dto);
        }
      }
    } catch (Exception _) {
    }
    if (paramMap == null) paramMap = new HashMap<>();

    String templateName = config.template();

    String exportName = replaceParam(paramMap, config.exportName());

    TempFile genFile = createTempFile(exportName + "." + Extension.XLSX.value, Extension.XLSX);
    TempFile genFile2 = createTempFile(exportName + "." + Extension.XLSX.value, Extension.XLSX);
    XSSFWorkbook wb = null;
    try (FileInputStream genFIS = new FileInputStream(genFile);
        FileOutputStream genFOS = new FileOutputStream(genFile2)) {

      File templateFile = new File(templateExcelFolder, templateName);
      Files.copy(templateFile, genFile);
      wb = new XSSFWorkbook(genFIS);
      genFIS.close();

      Map<Integer, CellStyle> cellStyleMap = new HashMap<>();
      if (StringUtils.isNotBlank(config.styleTemplateName())) {
        XSSFSheet styleSheet = wb.getSheet(config.styleTemplateName());
        if (styleSheet != null) {
          Row row0 = styleSheet.getRow(0);
          if (row0 != null) {
            for (Cell cell : row0) {
              cellStyleMap.put((int) cell.getNumericCellValue(), cell.getCellStyle());
            }
          }
        }
      }

      // =================================================================================================
      // Common export
      for (Field field : dto.getClass().getDeclaredFields()) {
        field.setAccessible(true);
        Object cellValue = field.get(dto);
        ValueDefinition def = field.getAnnotation(ValueDefinition.class);

        if (cellValue == null) continue;

        // ExcelExportForm
        ExcelExportForm[] allFormATs = field.getAnnotationsByType(ExcelExportForm.class);
        List<ExcelExportForm> formATs = new ArrayList<>();
        formAtLoop:
        for (ExcelExportForm e : allFormATs) {
          if (e.groups().length == 0) {
            formATs.add(e);
          } else {
            for (Class<?> clazz : e.groups()) {
              if (groupSet.contains(clazz)) {
                formATs.add(e);
                continue formAtLoop;
              }
            }
          }
        }
        if (!formATs.isEmpty()) {
          processForm(wb, formATs, cellValue, def);
        }
      }

      if (recalculateAfter) {
        try {
          XSSFFormulaEvaluator.evaluateAllFormulaCells(wb);
        } catch (Exception e) {
          log.error("", e);
        }
      }

      for (Sheet sheet : wb) {
        sheet.setZoom(100);
      }

      // Lock sheet
      if (config.lockSheetIndex().length > 0) {
        Set<Integer> sheetSet = new HashSet<>();
        for (int index : config.lockSheetIndex()) sheetSet.add(index);

        for (int index : sheetSet) {
          XSSFSheet sheet = wb.getSheetAt(index);

          sheet.lockSelectLockedCells(true);
          sheet.lockSelectUnlockedCells(true);
          sheet.setSheetPassword(excelSheetPassword, HashAlgorithm.md5);
          sheet.enableLocking();

          wb.lockStructure();
        }
      }
      // =================================================================================================
      wb.write(genFOS);
      genFOS.flush();

    } catch (Exception e) {
      log.error("exportToExcelTemplate has failed.", e);
      try {
        if (genFile != null) {
          if (genFile.delete()) {
            log.info("exportToExcelTemplate : genFile deleted.");
          } else {
            log.error("exportToExcelTemplate : genFile delete failed.");
          }
        }
      } catch (Exception e2) {
        log.error("exportToExcelTemplate : genFile delete failed.", e2);
      }

      try {
        if (genFile2 != null) {
          if (genFile2.delete()) {
            log.info("exportToExcelTemplate : genFile2 deleted.");
          } else {
            log.error("exportToExcelTemplate : genFile2 delete failed.");
          }
        }
      } catch (Exception e2) {
        log.error("exportToExcelTemplate : genFile2 delete failed.", e2);
      }
      genFile2 = null;

    } finally {
      try {
        if (wb != null) wb.close();
      } catch (Exception _) {
      }
      try {
        if (genFile != null) genFile.delete();
      } catch (Exception _) {
      }
    }
    return genFile2;
  }

  private void processForm(
      XSSFWorkbook wb, List<ExcelExportForm> formATs, Object cellValue, ValueDefinition def) {
    for (ExcelExportForm at : formATs) {
      XSSFSheet sheet = wb.getSheetAt(at.sheetIndex());
      String cellName = at.cell();

      ExcelPosition pos = new ExcelPosition(cellName);
      setCellValue(sheet, pos, cellValue, def);
    }
  }

  private Cell getCellByName(XSSFSheet sheet, String name) {
    ExcelPosition pos = new ExcelPosition(name);
    Row row = sheet.getRow(pos.row);
    if (row == null) return null;
    return row.getCell(pos.column);
  }

  private void setStringCellValue(Cell cell, String value) {
    if (cell != null && value != null) {
      if (value.length() >= EXCEL_CELL_STRING_MAX_SIZE) {
        cell.setCellValue(DATA_TOO_LONG);
      } else {
        cell.setCellValue(value);
      }
    }
  }

  private void setCellValue(XSSFSheet sheet, ExcelPosition pos, Object value, ValueDefinition def) {
    if (value == null) return;

    Row row = sheet.getRow(pos.row);
    if (row == null) row = sheet.createRow(pos.row);
    Cell cell = row.getCell(pos.column);
    if (cell == null) {
      cell = row.createCell(pos.column);
      cell.setCellType(CellType.STRING);
    }

    switch (value) {
      case Date date -> {
        if (def != null) {
          if (StringUtils.isBlank(def.dateFormat())) {
            cell.setCellValue(date);
          } else {
            String dateTime = DatetimeUtil.formatDateToString(date, def.dateFormat());
            setStringCellValue(cell, dateTime);
          }
        } else {
          cell.setCellValue(date);
        }
      }
      case Number number -> {
        if (value instanceof Double || value instanceof BigDecimal) {
          BigDecimal tmp =
              value instanceof Double ? new BigDecimal((Double) value) : (BigDecimal) value;
          if (def != null) {
            try {
              tmp = tmp.setScale(def.decimalScale(), def.roundingMode());
            } catch (Exception e) {
              tmp = tmp.setScale(def.decimalScale(), RoundingMode.HALF_EVEN);
            }
          }
          cell.setCellValue(tmp.doubleValue());
        } else if (value instanceof Integer tmp) {
          cell.setCellValue(tmp);
        } else if (value instanceof BigInteger tmp) {
          cell.setCellValue(tmp.intValue());
        }
        cell.setCellType(CellType.NUMERIC);
      }
      case String s -> {
        setStringCellValue(cell, s);
        cell.setCellType(CellType.STRING);
      }
      case Boolean b -> {
        cell.setCellValue(b);
        cell.setCellType(CellType.BOOLEAN);
      }
      default -> {}
    }
  }

  private void setCellStyle(
      XSSFSheet sheet,
      ExcelPosition pos,
      Map<Integer, CellStyle> cellStyleMap,
      Integer cellStyleId) {
    if (cellStyleId == null) return;

    Row row = sheet.getRow(pos.row);
    if (row == null) row = sheet.createRow(pos.row);
    Cell cell = row.getCell(pos.column);
    if (cell == null) {
      cell = row.createCell(pos.column);
      cell.setCellType(CellType.STRING);
    }
    cell.setCellStyle(cellStyleMap.get(cellStyleId));
  }

  private String replaceParam(Map<String, String> params, String input) {
    String result = input;

    Matcher m = Pattern.compile("\\{(\\w+)}").matcher(input);
    while (m.find()) {
      String key = m.group(1);
      if (params.containsKey(key) && StringUtils.isNotBlank(params.get(key)))
        result = result.replaceAll("\\{" + key + "}", params.get(key));
    }
    return result;
  }

  public static class ExcelTarget {
    private final Map<Integer, List<ExcelPosition>> targetMap = new HashMap<>();

    public void add(int sheetIndex, String cellAddress) {
      List<ExcelPosition> posList = targetMap.computeIfAbsent(sheetIndex, _ -> new ArrayList<>());
      ExcelPosition pos = new ExcelPosition(cellAddress);
      posList.add(pos);
    }

    public void add(int sheetIndex, int row, int column) {
      List<ExcelPosition> posList = targetMap.computeIfAbsent(sheetIndex, _ -> new ArrayList<>());
      ExcelPosition pos = new ExcelPosition(column, row);
      posList.add(pos);
    }
  }

  public TempFile markCellError(
      XSSFWorkbook wb, ExcelTarget newTarget, TempFile sourceFile, List<String> errorList) {
    String originalName = sourceFile.getBusinessName();
    TempFile resultFile = createTempFile(originalName, Extension.getFromName(originalName));
    try (FileOutputStream fos = new FileOutputStream(resultFile)) {
      ObjectMapper mapper = new ObjectMapper();
      mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
      mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

      // ===========================================================================
      // Clear error
      XSSFSheet errorShet = wb.getSheet(excelSheetError);
      if (errorShet != null
          && errorShet.getRow(0) != null
          && errorShet.getRow(0).getCell(0) != null
          && StringUtils.isNotBlank(errorShet.getRow(0).getCell(0).getStringCellValue())) {

        Font noErrorFont = wb.createFont();
        noErrorFont.setColor(IndexedColors.BLACK.getIndex());
        noErrorFont.setBold(false);

        String oldError = errorShet.getRow(0).getCell(0).getStringCellValue().trim();
        ExcelTarget oldTarget =
            mapper.readValue(
                errorShet.getRow(0).getCell(0).getStringCellValue(), ExcelTarget.class);

        for (Map.Entry<Integer, List<ExcelPosition>> entry : oldTarget.targetMap.entrySet()) {
          Integer sheetIndex = entry.getKey();
          List<ExcelPosition> posList = entry.getValue();
          XSSFSheet sheet = wb.getSheetAt(sheetIndex);

          for (ExcelPosition pos : posList) {
            Row row = sheet.getRow(pos.row);
            if (row == null) continue;
            Cell cell = row.getCell(pos.column);
            if (cell == null) continue;
            cell.getCellStyle().setFont(noErrorFont);
          }
        }
      }

      for (Row row : Objects.requireNonNull(errorShet)) errorShet.removeRow(row);

      // ===========================================================================
      // Mark error
      Font errorFont = wb.createFont();
      errorFont.setColor(IndexedColors.RED.getIndex());
      errorFont.setBold(true);

      for (Map.Entry<Integer, List<ExcelPosition>> entry : newTarget.targetMap.entrySet()) {
        Integer sheetIndex = entry.getKey();
        List<ExcelPosition> posList = entry.getValue();
        XSSFSheet sheet = wb.getSheetAt(sheetIndex);

        for (ExcelPosition pos : posList) {
          Row row = sheet.getRow(pos.row);
          if (row == null) continue;
          Cell cell = row.getCell(pos.column);
          if (cell == null) continue;
          CellStyle cellStyle = wb.createCellStyle();
          cellStyle.cloneStyleFrom(cell.getCellStyle());
          cellStyle.setFont(errorFont);
          cell.setCellStyle(cellStyle);
        }
      }

      // ===========================================================================
      // Record error
      if (errorShet == null) errorShet = wb.createSheet(excelSheetError);

      // =====================================
      // error mapping
      Row row = errorShet.getRow(0);
      if (row == null) row = errorShet.createRow(0);
      Cell cell = row.getCell(0);
      if (cell == null) cell = row.createCell(0, CellType.STRING);
      String errorValue = mapper.writeValueAsString(newTarget);
      setStringCellValue(cell, errorValue);

      // =====================================
      // logging error
      int logIndex = 1;
      for (String errorLog : errorList) {
        row = errorShet.getRow(logIndex);
        if (row == null) row = errorShet.createRow(logIndex);

        Cell cellRowIndex = row.getCell(0);
        if (cellRowIndex == null) cellRowIndex = row.createCell(0, CellType.STRING);

        Cell cellContent = row.getCell(1);
        if (cellContent == null) cellContent = row.createCell(1, CellType.STRING);

        String[] errorArr = errorLog.split("-");
        if (errorArr.length == 1) {
          setStringCellValue(cellContent, errorArr[0]);
        } else if (errorArr.length == 2) {
          setStringCellValue(cellRowIndex, errorArr[0]);
          setStringCellValue(cellContent, errorArr[1]);
        }
        logIndex++;
      }

      // =====================================
      // locking error sheet
      errorShet.lockSelectLockedCells(true);
      errorShet.lockSelectUnlockedCells(true);
      errorShet.setSheetPassword(excelSheetPassword, HashAlgorithm.md5);
      errorShet.enableLocking();
      wb.lockStructure();

      // ===========================================================================
      // Write file

      wb.write(fos);
      fos.flush();
    } catch (Exception e) {
      resultFile = null;
      log.error("markCell has failed.", e);
    } finally {
      if (wb != null)
        try {
          wb.close();
        } catch (Exception _) {
        }
      try {
        if (sourceFile.delete()) {
          log.info("Delete file: {}", sourceFile.getAbsolutePath());
        }
      } catch (Exception _) {
      }
    }
    return resultFile;
  }

  // At 01:00:00am every day
  @Scheduled(cron = "0 0 1 * * ?")
  private void tempFileAutoCleaning() {
    log.info(" * * * * * * * * * * * * * * * * * * * ");
    log.info(" * * * * * * * * * * * * * * * * * * * ");
    log.info(" *   Temp folder auto cleaning ..    * ");
    log.info(" * * * * * * * * * * * * * * * * * * * ");
    long curentTime = System.currentTimeMillis();
    long TTL = (long) tempFileTTL * 60 * 60 * 1000;
    log.info("Allowed Time to live : {} milliseconds.", TTL);
    log.info(" * * * * * * * * * * * * * * * * * * * ");
    if (TempFolder != null && TempFolder.listFiles() != null) {
      for (File file : Objects.requireNonNull(TempFolder.listFiles())) {
        if (!file.isFile()) continue;

        BasicFileAttributes attr = null;
        try {
          attr = java.nio.file.Files.readAttributes(file.toPath(), BasicFileAttributes.class);
        } catch (IOException e1) {
          log.warn("Can not read temp file {}", file.getName());
        }
        if (attr != null) {
          long liveTime = curentTime - attr.creationTime().toMillis();
          if (liveTime > TTL) {
            try {
              if (file.delete()) {
                log.info(
                    "Deleting file : [{}]. Created time : {}. Lived time : {} milliseconds.",
                    file.getName(),
                    attr.creationTime().toString(),
                    liveTime);
              }
            } catch (Exception e) {
              log.warn("Can not delete temp file {}", file.getName());
            }
          }
        }
      }
      // clean up temporary physical cache folder for downloading file

      for (File file : Objects.requireNonNull(TempFolder.listFiles())) {
        if (file.isFile()) continue;

        try {
          long liveTime = curentTime - Long.parseLong(file.getName());
          if (liveTime > TTL) {
            if (file.delete()) {
              log.debug("Clear tmp cache folder {}", file.getName());
            }
          }
        } catch (Exception e) {
          log.warn("Can not delete temp cache folder {}", file.getName());
        }
      }
    }

    log.info(" * * * * * * * * * * * * * * * * * * * ");
  }

  public static String encodeFileToStringBase64(File file, boolean deleteAfter) {
    String result = null;
    if (file == null) return null;
    try (FileInputStream fis = new FileInputStream(file)) {
      result = Base64.encodeBase64String(IOUtils.toByteArray(fis));
    } catch (Exception e) {
      log.error("", e);
    } finally {
      if (deleteAfter) {
        try {
          if (file.delete()) {
            log.info("Delete file: {}", file.getAbsolutePath());
          }
        } catch (Exception _) {
        }
      }
    }
    return result;
  }

  public static Map<String, MultipartFile> converListToMapMultiPartFile(
      List<MultipartFile> multipartFiles) {
    if (multipartFiles == null || multipartFiles.isEmpty()) {
      return Collections.emptyMap();
    }

    return multipartFiles.stream()
        .collect(Collectors.toMap(MultipartFile::getOriginalFilename, Function.identity()));
  }

  public <T> void removeRowEmpty(File file, int size, T dto) {

    try (InputStream is = new FileInputStream(file)) {

      Workbook workbook = new XSSFWorkbook(is);

      Sheet sheet = workbook.getSheetAt(0);

      Class<?> classType = dto.getClass();

      ExcelExportGeneralConfig config = classType.getAnnotation(ExcelExportGeneralConfig.class);

      for (int i = sheet.getLastRowNum() + 1; i > config.startRow() + size; i--) {

        sheet.shiftRows(i, sheet.getLastRowNum() + 1, -1);
      }
      try (FileOutputStream outputStream = new FileOutputStream(file)) {

        workbook.write(outputStream);

      } catch (IOException e) {

        throw new CustomException(e.getLocalizedMessage());
      }

    } catch (IOException e) {
      log.error(e.getMessage(), e);
    }
  }

  public void generatePdfFromHtml(String html, HttpServletResponse response)
      throws IOException, ParserConfigurationException, SAXException {
    final DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
    documentBuilderFactory.setValidating(false);
    documentBuilderFactory.setExpandEntityReferences(false);

    DocumentBuilder builder = documentBuilderFactory.newDocumentBuilder();
    builder.setEntityResolver(FSEntityResolver.instance());
    org.w3c.dom.Document document =
        builder.parse(new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
    File tempFile;

    String tempPathFile = "/var/tmp/templates/pdf/" + StringUtil.genUniqueText() + ".pdf";
    tempFile = new File(tempPathFile);

    ITextRenderer renderer = new ITextRenderer();
    String templatePath = "/var/tmp/fonts/arial.ttf";
    try {
      renderer.getFontResolver().addFont(templatePath, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
    } catch (Exception e) {
      if (tempFile.delete()) {
        log.info("Delete file: {}", tempFile.getAbsolutePath());
      } else {
        log.error("Can not delete file: {}", tempFile.getAbsolutePath());
      }
      throw new CustomException("Error when add font: " + e.getMessage() + templatePath);
    }

    try {
      OutputStream outputStream = new FileOutputStream(tempFile);
      renderer.setDocument(document, null);
      renderer.layout();
      renderer.createPDF(outputStream);
      outputStream.close();
    } catch (Exception e) {
      if (tempFile.delete()) {
        log.info("Delete file: {}", tempFile.getAbsolutePath());
      } else {
        log.error("Can not delete file: {}", tempFile.getAbsolutePath());
      }
      throw new CustomException("Error when createPDF: " + e.getMessage());
    }

    try (FileInputStream fis = new FileInputStream(tempFile)) {
      response.setContentType(CommonConstant.MimeType.PDF);
      response.setHeader("Content-Disposition", "attachment;");
      response.setContentLength((int) tempFile.length());
      FileCopyUtils.copy(fis, response.getOutputStream());
      response.flushBuffer();
    } catch (IOException e) {
      log.error("{}", String.valueOf(e));
      if (tempFile.delete()) {
        log.info("Delete file: {}", tempFile.getAbsolutePath());
      } else {
        log.error("Can not delete file: {}", tempFile.getAbsolutePath());
      }
      throw new CustomException("Error when send file: " + e.getMessage());
    } finally {
      if (tempFile.delete()) {
        log.info("Delete file: {}", tempFile.getAbsolutePath());
      } else {
        log.error("Can not delete file: {}", tempFile.getAbsolutePath());
      }
    }
  }

  public File generatePdfFromHtmlToFile(String html)
      throws IOException, ParserConfigurationException, SAXException {
    final DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
    documentBuilderFactory.setValidating(false);
    documentBuilderFactory.setExpandEntityReferences(false);
    DocumentBuilder builder = documentBuilderFactory.newDocumentBuilder();
    builder.setEntityResolver(FSEntityResolver.instance());
    org.w3c.dom.Document document =
        builder.parse(new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
    File tempFile;

    String tempPathFile = "/var/tmp/templates/pdf/" + StringUtil.genUniqueText() + ".pdf";

    tempFile = new File(tempPathFile);

    ITextRenderer renderer = new ITextRenderer();

    String templatePath = "/var/tmp/fonts/arial.ttf";

    try {
      renderer.getFontResolver().addFont(templatePath, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
    } catch (Exception e) {
      if (tempFile.delete()) {
        log.info("Delete file: {}", tempFile.getAbsolutePath());
      } else {
        log.error("Can not delete file: {}", tempFile.getAbsolutePath());
      }
      throw new CustomException("Error when add font: " + e.getMessage() + templatePath);
    }

    try {
      OutputStream outputStream = new FileOutputStream(tempFile);
      renderer.setDocument(document, null);
      renderer.layout();
      renderer.createPDF(outputStream);
      outputStream.close();
    } catch (Exception e) {
      if (tempFile.delete()) {
        log.info("Delete file: {}", tempFile.getAbsolutePath());
      } else {
        log.error("Can not delete file: {}", tempFile.getAbsolutePath());
      }
      throw new CustomException("Error when createPDF: " + e.getMessage());
    }

    try {
      return tempFile;
    } catch (Exception e) {
      if (tempFile.delete()) {
        log.info("Delete file: {}", tempFile.getAbsolutePath());
      } else {
        log.error("Can not delete file: {}", tempFile.getAbsolutePath());
      }
      throw new CustomException("Error when send file: " + e.getMessage());
    }
  }

  public static Map<String, Object> uploadFile(MultipartFile file) {
    return uploadFile(file, null);
  }

  public static Map<String, Object> uploadFile(MultipartFile file, String targetBucket) {
    String bucket = (targetBucket != null && !targetBucket.isBlank()) ? targetBucket : bucketName;
    String originalFilename = file.getOriginalFilename();
    String savedFileName =
        org.springframework.util.StringUtils.cleanPath(
            SnowflakeIdGenerator.generateId() + "_" + originalFilename);

    try {
      MinioClient minioClient =
          MinioClient.builder().endpoint(endPoint).credentials(accessKey, secretKey).build();

      boolean bucketExists =
          minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
      if (!bucketExists) {
        minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
      }

      PutObjectArgs putObjectArgs =
          PutObjectArgs.builder().bucket(bucket).object(savedFileName).stream(
                  new ByteArrayInputStream(file.getBytes()), file.getSize(), -1)
              .build();

      minioClient.putObject(putObjectArgs);
    } catch (MinioException
        | InvalidKeyException
        | IOException
        | NoSuchAlgorithmException
        | IllegalArgumentException e) {
      throw new CustomException("Error occurred: " + e);
    }

    String mineType =
        Objects.requireNonNull(savedFileName)
            .substring(savedFileName.lastIndexOf(".") + 1)
            .toLowerCase();

    Map<String, Object> data = new HashMap<>();
    data.put("fileName", savedFileName);
    data.put("mineType", mineType);
    data.put("originalName", originalFilename);
    data.put("size", file.getSize());
    data.put("bucketName", bucket);
    return data;
  }

  /**
   * Upload byte array lên MinIO với custom object name
   *
   * @param fileBytes Nội dung file dưới dạng byte array
   * @param customObjectName Tên object trên MinIO (bao gồm cả path), ví dụ:
   *     dvc-connect/UPLOAD/123/cert.pdf
   * @param originalFilename Tên file gốc để extract extension
   * @param targetBucket Bucket đích (nullable, mặc định là bucketName)
   * @return Map chứa thông tin file: fileName, mineType, originalName, size, bucketName
   */
  public static Map<String, Object> uploadByteArrayWithCustomPath(
      byte[] fileBytes, String customObjectName, String originalFilename, String targetBucket) {
    String bucket = (targetBucket != null && !targetBucket.isBlank()) ? targetBucket : bucketName;

    try {
      MinioClient minioClient =
          MinioClient.builder().endpoint(endPoint).credentials(accessKey, secretKey).build();

      boolean bucketExists =
          minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
      if (!bucketExists) {
        minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
      }

      PutObjectArgs putObjectArgs =
          PutObjectArgs.builder().bucket(bucket).object(customObjectName).stream(
                  new ByteArrayInputStream(fileBytes), fileBytes.length, -1)
              .build();

      minioClient.putObject(putObjectArgs);
    } catch (MinioException
        | InvalidKeyException
        | IOException
        | NoSuchAlgorithmException
        | IllegalArgumentException e) {
      throw new CustomException("Error occurred: " + e);
    }

    String mineType =
        originalFilename.substring(originalFilename.lastIndexOf(".") + 1).toLowerCase();

    Map<String, Object> data = new HashMap<>();
    data.put("fileName", customObjectName); // Lưu custom path thay vì auto-generated name
    data.put("mineType", mineType);
    data.put("originalName", originalFilename);
    data.put("size", (long) fileBytes.length);
    data.put("bucketName", bucket);
    return data;
  }

  public static String getDefaultBucket() {
    return bucketName;
  }

  public static String getPublicBucket() {
    return publicBucketName;
  }

  public static String getDvcBucket() {
    return dvcBucketName;
  }

  public static byte[] getFileContent(String fileName) {
    return getFileContent(fileName, null);
  }

  public static byte[] getFileContent(String fileName, String targetBucket) {
    String bucket = (targetBucket != null && !targetBucket.isBlank()) ? targetBucket : bucketName;
    try {
      MinioClient minioClient =
          MinioClient.builder().endpoint(endPoint).credentials(accessKey, secretKey).build();

      GetObjectArgs args = GetObjectArgs.builder().bucket(bucket).object(fileName).build();
      InputStream obj = minioClient.getObject(args);
      byte[] content = IOUtils.toByteArray(obj);
      obj.close();
      return content;
    } catch (MinioException
        | InvalidKeyException
        | IOException
        | NoSuchAlgorithmException
        | IllegalArgumentException e) {
      e.printStackTrace();
    }
    return null;
  }

  public static Resource downloadFile(String fileName) {
    byte[] bytes = getFileContent(fileName);
    if (bytes == null) {
      throw new CustomException("Không tìm thấy file.");
    }

    return new ByteArrayResource(bytes);
  }

  public static String downloadFileAsBase64(String fileName) {
    if (fileName == null || fileName.isBlank()) {
      logger.error("Tên file không được để trống hoặc null");
      throw new IllegalArgumentException("Tên file không được để trống hoặc null");
    }

    logger.info("Đang tải file từ MinIO: {}", fileName);
    try (InputStream inputStream = getFileContentStream(fileName)) {
      byte[] fileBytes = inputStream.readAllBytes();
      logger.info("Mã hóa file thành Base64: {}", fileName);
      return Base64.encodeBase64String(fileBytes);
    } catch (CustomException e) {
      logger.error("Lỗi MinIO khi tải file: {}", fileName, e);
      throw e;
    } catch (Exception e) {
      logger.error("Lỗi không mong muốn khi tải file: {}", fileName, e);
      throw new CustomException("Lỗi không mong muốn khi tải file: " + fileName);
    }
  }

  public static InputStream getFileContentStream(String fileName) {
    return getFileContentStream(fileName, null);
  }

  public static InputStream getFileContentStream(String fileName, String targetBucket) {
    String bucket = (targetBucket != null && !targetBucket.isBlank()) ? targetBucket : bucketName;
    try {
      MinioClient minioClient =
          MinioClient.builder().endpoint(endPoint).credentials(accessKey, secretKey).build();

      GetObjectArgs args = GetObjectArgs.builder().bucket(bucket).object(fileName).build();
      return minioClient.getObject(args);
    } catch (io.minio.errors.ErrorResponseException e) {
      throw new CustomException("ERMINIO: Không tìm thấy file " + fileName);
    } catch (Exception e) {
      throw new CustomException("Lỗi MinIO khi tải file: " + fileName);
    }
  }

  public static void deleteFile(String fileName) {
    deleteFile(fileName, null);
  }

  public static void deleteFile(String fileName, String targetBucket) {
    String bucket = (targetBucket != null && !targetBucket.isBlank()) ? targetBucket : bucketName;
    try {
      MinioClient minioClient =
          MinioClient.builder().endpoint(endPoint).credentials(accessKey, secretKey).build();

      boolean bucketExists =
          minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
      if (!bucketExists) {
        minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
      }

      minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(fileName).build());
      log.info("{} was successfully deleted from bucket {}", fileName, bucket);
    } catch (IOException
        | MinioException
        | IllegalArgumentException
        | NoSuchAlgorithmException
        | InvalidKeyException e) {
      e.printStackTrace();
    }
  }

  public static Boolean checkUploadFileIsValid(MultipartFile file) {
    // Check if the file's name contains invalid characters
    List<String> whiteList =
        Arrays.asList("jpeg", "jpg", "png", "pdf", "xls", "xlsx", "doc", "docx", "txt");
    if (Objects.requireNonNull(file.getOriginalFilename()).contains("..")) {
      throw new UpdateException("Sorry! Filename contains invalid path sequence");
    }

    String mineType =
        file.getOriginalFilename()
            .substring(file.getOriginalFilename().lastIndexOf(".") + 1)
            .toLowerCase();
    if (!whiteList.contains(mineType)) {
      throw new UpdateException("Sorry! File extension on restrict list");
    }

    return true;
  }

  public static Boolean checkFileIsValid(MultipartFile file) {
    // Check if the file's name contains invalid characters
    List<String> whiteList =
        Arrays.asList("jpeg", "jpg", "png", "pdf", "xls", "xlsx", "doc", "docx", "txt", "mp4");
    if (Objects.requireNonNull(file.getOriginalFilename()).contains("..")) {
      throw new UpdateException("Sorry! Filename contains invalid path sequence");
    }

    String mineType =
        file.getOriginalFilename()
            .substring(file.getOriginalFilename().lastIndexOf(".") + 1)
            .toLowerCase();
    if (!whiteList.contains(mineType)) {
      throw new UpdateException("Sorry! File extension on restrict list");
    }

    return true;
  }

  public static Boolean checkFileIsValidWithMp3Mp4(MultipartFile file) {
    // Check if the file's name contains invalid characters
    List<String> whiteList =
        Arrays.asList(
            "jpeg", "jpg", "png", "pdf", "xls", "xlsx", "doc", "docx", "txt", "mp3", "mp4", "wmv");
    if (Objects.requireNonNull(file.getOriginalFilename()).contains("..")) {
      throw new UpdateException("Sorry! Filename contains invalid path sequence");
    }

    String mineType =
        file.getOriginalFilename()
            .substring(file.getOriginalFilename().lastIndexOf(".") + 1)
            .toLowerCase();
    if (!whiteList.contains(mineType)) {
      throw new UpdateException("Sorry! File extension on restrict list");
    }

    return true;
  }

  public static Boolean checkUploadFileIsValidImage(MultipartFile file) {
    // Check if the file's name contains invalid characters
    List<String> whiteList = Arrays.asList("jpeg", "jpg", "png", "gif", "tiff");
    if (Objects.requireNonNull(file.getOriginalFilename()).contains("..")) {
      throw new UpdateException("Sorry! Filename contains invalid path sequence");
    }

    String mineType =
        file.getOriginalFilename()
            .substring(file.getOriginalFilename().lastIndexOf(".") + 1)
            .toLowerCase();
    if (!whiteList.contains(mineType)) {
      throw new UpdateException("Sorry! File extension on restrict list");
    }

    return true;
  }

  public static File convertMultipartFileToFile(MultipartFile file) {
    File convFile = new File(Objects.requireNonNull(file.getOriginalFilename()));
    try {
      convFile.createNewFile();
      FileOutputStream fos = new FileOutputStream(convFile);
      fos.write(file.getBytes());
      fos.close(); // IOUtils.closeQuietly(fos);
    } catch (IOException e) {
      convFile = null;
    }

    return convFile;
  }

  public static Map<String, Object> uploadFileHeThong(MultipartFile file) {
    String originalFilename = file.getOriginalFilename();
    String savedFileName =
        subDirectory
            + org.springframework.util.StringUtils.cleanPath(
                (new Date()).getTime() + "_" + originalFilename);
    try {
      MinioClient minioClient =
          MinioClient.builder().endpoint(endPoint).credentials(accessKey, secretKey).build();

      boolean bucketExists =
          minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
      if (!bucketExists) {
        minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
      }

      PutObjectArgs putObjectArgs =
          PutObjectArgs.builder().bucket(bucketName).object(savedFileName).stream(
                  new ByteArrayInputStream(file.getBytes()), file.getSize(), -1)
              .build();

      minioClient.putObject(putObjectArgs);

    } catch (MinioException
        | InvalidKeyException
        | IOException
        | NoSuchAlgorithmException
        | IllegalArgumentException e) {

      log.error(e.getMessage(), e);
      throw new CustomException("Error occurred: " + e);
    }

    String mineType =
        Objects.requireNonNull(savedFileName)
            .substring(savedFileName.lastIndexOf(".") + 1)
            .toLowerCase();

    Map<String, Object> data = new HashMap<>();
    data.put("fileName", savedFileName);
    data.put("mineType", mineType);
    data.put("originalName", originalFilename);
    data.put("size", file.getSize());
    return data;
  }

  /** Upload file vào bucket cụ thể */
  public static Map<String, Object> uploadFileToBucket(
      MultipartFile file, String targetBucket, String targetSubDir) {
    String originalFilename = file.getOriginalFilename();
    String savedFileName =
        (targetSubDir != null ? targetSubDir + "/" : "")
            + org.springframework.util.StringUtils.cleanPath(
                (new Date()).getTime() + "_" + originalFilename);

    try {
      MinioClient minioClient =
          MinioClient.builder().endpoint(endPoint).credentials(accessKey, secretKey).build();

      boolean bucketExists =
          minioClient.bucketExists(BucketExistsArgs.builder().bucket(targetBucket).build());
      if (!bucketExists) {
        minioClient.makeBucket(MakeBucketArgs.builder().bucket(targetBucket).build());
      }

      PutObjectArgs putObjectArgs =
          PutObjectArgs.builder().bucket(targetBucket).object(savedFileName).stream(
                  new ByteArrayInputStream(file.getBytes()), file.getSize(), -1)
              .contentType(file.getContentType())
              .build();

      minioClient.putObject(putObjectArgs);

    } catch (Exception e) {
      log.error("Lỗi upload file sang bucket {}: {}", targetBucket, e.getMessage());
      throw new CustomException("Error occurred: " + e.getMessage());
    }

    String mineType =
        Objects.requireNonNull(savedFileName)
            .substring(savedFileName.lastIndexOf(".") + 1)
            .toLowerCase();

    Map<String, Object> data = new HashMap<>();
    data.put("fileName", savedFileName);
    data.put("bucketName", targetBucket);
    data.put("mineType", mineType);
    data.put("originalName", originalFilename);
    data.put("size", file.getSize());
    return data;
  }

  public static Boolean checkUploadFileIsValidLocalMap(MultipartFile file) {
    // Check if the file's name contains invalid characters
    List<String> whiteList =
        Arrays.asList(
            "jpeg", "jpg", "png", "pdf", "xls", "xlsx", "doc", "docx", "txt", "kmz", "kml", "shp",
            "cad");
    if (Objects.requireNonNull(file.getOriginalFilename()).contains("..")) {
      throw new UpdateException("Sorry! Filename contains invalid path sequence");
    }

    String mineType =
        file.getOriginalFilename()
            .substring(file.getOriginalFilename().lastIndexOf(".") + 1)
            .toLowerCase();
    if (!whiteList.contains(mineType)) {
      throw new UpdateException("Sorry! File extension on restrict list");
    }

    return true;
  }

  public static Boolean uploadImageFileValidator(MultipartFile file) {
    // Check if the file's name contains invalid characters
    List<String> whiteList = Arrays.asList("jpeg", "jpg", "png");
    if (Objects.requireNonNull(file.getOriginalFilename()).contains("..")) {
      throw new UpdateException("Sorry! Filename contains invalid path sequence");
    }

    String mineType =
        file.getOriginalFilename()
            .substring(file.getOriginalFilename().lastIndexOf(".") + 1)
            .toLowerCase();
    if (!whiteList.contains(mineType)) {
      throw new UpdateException("Sorry! File extension on restrict list");
    }

    return true;
  }

  public static boolean checkValidUploadImgFile(MultipartFile file) {
    List<String> whiteList = Arrays.asList("jpeg", "jpg", "png");
    if (Objects.requireNonNull(file.getOriginalFilename()).contains("..")) {
      return false;
    }

    String mineType =
        file.getOriginalFilename()
            .substring(file.getOriginalFilename().lastIndexOf(".") + 1)
            .toLowerCase();
    return whiteList.contains(mineType);
  }

  public static boolean checkValidUploadDocFile(MultipartFile file) {
    List<String> whiteList = Arrays.asList("pdf", "xls", "xlsx", "doc", "docx", "txt");
    if (Objects.requireNonNull(file.getOriginalFilename()).contains("..")) {
      return false;
    }

    String mineType =
        file.getOriginalFilename()
            .substring(file.getOriginalFilename().lastIndexOf(".") + 1)
            .toLowerCase();
    return whiteList.contains(mineType);
  }

  public static long getTotalFileSize(List<MultipartFile> multipartFiles) {
    AtomicReference<Long> totalSize = new AtomicReference<>(0L);
    multipartFiles.forEach(file -> totalSize.set(totalSize.get() + file.getSize()));
    return totalSize.get();
  }

  public static MultipartFile getNewFile(String fileName, MultipartFile currentFile) {
    return new MultipartFile() {
      @Override
      public String getName() {
        return currentFile.getName();
      }

      @Override
      public String getOriginalFilename() {
        return fileName;
      }

      @Override
      public String getContentType() {
        return currentFile.getContentType();
      }

      @Override
      public boolean isEmpty() {
        return currentFile.isEmpty();
      }

      @Override
      public long getSize() {
        return currentFile.getSize();
      }

      @Override
      public byte[] getBytes() throws IOException {
        return currentFile.getBytes();
      }

      @Override
      public InputStream getInputStream() throws IOException {
        return currentFile.getInputStream();
      }

      @Override
      public void transferTo(File file) throws IllegalStateException {}
    };
  }
}
