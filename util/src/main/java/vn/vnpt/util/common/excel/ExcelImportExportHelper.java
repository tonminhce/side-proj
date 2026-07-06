package vn.vnpt.util.common.excel;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFDataValidation;
import org.apache.poi.xssf.usermodel.XSSFName;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.web.multipart.MultipartFile;
import vn.vnpt.util.common.TemplateExcelWriter;
import vn.vnpt.util.common.excel.common.ExcelType;
import vn.vnpt.util.exception.CustomException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Bộ hàm tiện ích dùng chung cho các luồng import/export Excel.
 *
 * <p>Class này chỉ xử lý phần kỹ thuật lặp lại như validate file, đọc DTO từ Excel,
 * đổi file template sang byte[], tạo header download và merge cell. Không đặt validate
 * nghiệp vụ, map danh mục, lưu DB hoặc audit ở đây.</p>
 */
public final class ExcelImportExportHelper {

    private static final String XLS_EXTENSION = ".xls";
    private static final String XLSX_EXTENSION = ".xlsx";

    private ExcelImportExportHelper() {
    }

    /**
     * Kiểm tra file upload có phải file Excel hợp lệ trước khi đi vào bước đọc dữ liệu.
     *
     * <p>HDSD: gọi ở đầu hàm pre-import, trước khi tạo {@link XSSFWorkbook}. Hàm kiểm tra
     * 3 lỗi kỹ thuật thường gặp: file rỗng, vượt dung lượng cho phép, và tên file không
     * kết thúc bằng {@code .xls} hoặc {@code .xlsx}.</p>
     *
     * <p>Code mẫu:</p>
     * <pre>{@code
     * // Đầu vào: file "ImportDanhSach.xlsx", maxSize = 5MB
     * ExcelImportExportHelper.validateExcelFile(
     *     file,
     *     5 * 1024 * 1024,
     *     "File excel không được để trống",
     *     "File excel vượt quá dung lượng cho phép",
     *     "File excel không đúng định dạng"
     * );
     *
     * // Đầu ra:
     * // - File hợp lệ: không trả gì, cho phép chạy tiếp.
     * // - File rỗng/sai đuôi/quá dung lượng: throw CustomException với message tương ứng.
     * }</pre>
     *
     * @param file file Excel người dùng upload.
     * @param maxSize dung lượng tối đa tính theo byte.
     * @param emptyMessage thông báo lỗi khi file rỗng.
     * @param sizeMessage thông báo lỗi khi file vượt dung lượng.
     * @param typeMessage thông báo lỗi khi file không đúng định dạng Excel.
     * @throws CustomException khi file không đạt một trong các điều kiện trên.
     */
    public static void validateExcelFile(
            MultipartFile file,
            long maxSize,
            String emptyMessage,
            String sizeMessage,
            String typeMessage
    ) {
        if (file == null || file.isEmpty()) {
            throw new CustomException(emptyMessage);
        }
        if (file.getSize() > maxSize) {
            throw new CustomException(sizeMessage);
        }

        String fileName = file.getOriginalFilename();
        if (fileName == null || !hasExcelExtension(fileName)) {
            throw new CustomException(typeMessage);
        }
    }

    /**
     * Đọc toàn bộ dòng dữ liệu từ file Excel upload sang danh sách DTO theo annotation import.
     *
     * <p>HDSD: dùng sau {@link #validateExcelFile(MultipartFile, long, String, String, String)}.
     * DTO truyền vào cần khai báo các annotation mà {@link ExcelReader} đang hỗ trợ như
     * {@code @ExcelImportConfig}, {@code @ExcelImport}, {@code @ExcelImportLineIndex}. Hàm tự
     * mở/đóng workbook và trả về {@code rowDataList}.</p>
     *
     * <p>Code mẫu:</p>
     * <pre>{@code
     * // Đầu vào: file Excel + DTO có annotation import
     * List<MyImportDto> rows = ExcelImportExportHelper.readRows(
     *     file,
     *     MyImportDto.class,
     *     excelReader
     * );
     *
     * // Đầu ra: List<MyImportDto> đã được map từ sheet Excel.
     * // Ví dụ: [MyImportDto(code="A001", name="Tên A"), MyImportDto(code="A002", name="Tên B")]
     * }</pre>
     *
     * @param file file Excel người dùng upload.
     * @param dtoClass class DTO import cần map dữ liệu.
     * @param excelReader reader hiện có của project.
     * @param <T> kiểu DTO import.
     * @return danh sách DTO đọc được từ sheet cấu hình trong DTO.
     * @throws CustomException khi không đọc được workbook hoặc reader không hợp lệ.
     */
    public static <T> List<T> readRows(MultipartFile file, Class<T> dtoClass, ExcelReader excelReader) {
        if (excelReader == null) {
            throw new CustomException("ExcelReader không được null");
        }
        try (XSSFWorkbook workbook = new XSSFWorkbook(file.getInputStream())) {
            return excelReader.readFile(workbook, dtoClass).getRowDataList();
        } catch (IOException exception) {
            throw new CustomException("Đọc file Excel thất bại: " + exception.getMessage());
        }
    }

    /**
     * Bỏ các dòng dữ liệu ví dụ trong file import mẫu.
     *
     * <p>HDSD: sau khi đọc rows, truyền vào hàm lấy mã dòng như {@code Dto::getCode}
     * và chuỗi nhận diện dòng ví dụ, ví dụ {@code "Dòng ví dụ minh họa"}. Hàm giữ lại
     * các dòng có mã khác null và mã không chứa chuỗi ví dụ.</p>
     *
     * <p>Code mẫu:</p>
     * <pre>{@code
     * // Đầu vào:
     * // rows = [
     * //   Dto(code="Dòng ví dụ minh họa"),
     * //   Dto(code="TACN001"),
     * //   Dto(code=null)
     * // ]
     * List<Dto> validRows = ExcelImportExportHelper.filterExampleRows(
     *     rows,
     *     Dto::getCode,
     *     "Dòng ví dụ minh họa"
     * );
     *
     * // Đầu ra:
     * // validRows = [Dto(code="TACN001")]
     * }</pre>
     *
     * @param rows danh sách dòng đọc từ Excel.
     * @param codeGetter hàm lấy mã dòng để nhận diện dòng ví dụ.
     * @param exampleText chuỗi đánh dấu dòng ví dụ cần bỏ.
     * @param <T> kiểu DTO import.
     * @return danh sách đã bỏ dòng ví dụ.
     */
    public static <T> List<T> filterExampleRows(List<T> rows, Function<T, String> codeGetter, String exampleText) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        return rows.stream()
                .filter(Objects::nonNull)
                .filter(row -> {
                    String code = codeGetter.apply(row);
                    return code != null && (exampleText == null || !code.contains(exampleText));
                })
                .toList();
    }

    /**
     * Kiểm tra số dòng import không vượt giới hạn cho phép.
     *
     * <p>HDSD: gọi sau khi lọc dòng ví dụ và trước khi validate nghiệp vụ từng dòng.
     * Hàm chỉ so sánh số lượng, không kiểm tra nội dung từng dòng.</p>
     *
     * <p>Code mẫu:</p>
     * <pre>{@code
     * // Đầu vào: rowCount = 1001, maxRows = 1000
     * ExcelImportExportHelper.assertMaxRows(
     *     rows.size(),
     *     1000,
     *     "File excel vượt quá số dòng cho phép"
     * );
     *
     * // Đầu ra:
     * // - rowCount <= maxRows: không trả gì.
     * // - rowCount > maxRows: throw CustomException("File excel vượt quá số dòng cho phép").
     * }</pre>
     *
     * @param rowCount số dòng cần import.
     * @param maxRows số dòng tối đa cho phép.
     * @param message thông báo lỗi khi vượt giới hạn.
     * @throws CustomException khi {@code rowCount > maxRows}.
     */
    public static void assertMaxRows(int rowCount, int maxRows, String message) {
        if (rowCount > maxRows) {
            throw new CustomException(message);
        }
    }

    /**
     * Ghi dữ liệu vào template Excel một sheet bằng {@link TemplateExcelWriter}.
     *
     * <p>HDSD: dùng cho case export/template chỉ cần truyền DTO và không cần đổi tên sheet,
     * đổi tiêu đề, xóa cột hoặc ẩn cột. Đây là wrapper gom điểm gọi
     * {@code TemplateExcelWriter.setSingleDataToTemplate(data)} về một nơi dễ tìm.</p>
     *
     * <p>Code mẫu:</p>
     * <pre>{@code
     * // Đầu vào: exportDto có annotation ExcelExportGeneralConfig
     * File file = ExcelImportExportHelper.writeSingleTemplate(excelWriter, exportDto);
     *
     * // Đầu ra:
     * // file = file Excel tạm đã được ghi dữ liệu từ template.
     * }</pre>
     *
     * @param excelWriter writer hiện có của project.
     * @param data DTO export/template.
     * @param <T> kiểu DTO export/template.
     * @return file Excel tạm do {@link TemplateExcelWriter} sinh ra.
     */
    public static <T> File writeSingleTemplate(TemplateExcelWriter excelWriter, T data) {
        requireExcelWriter(excelWriter);
        return excelWriter.setSingleDataToTemplate(data);
    }

    /**
     * Ghi dữ liệu vào template Excel một sheet, có hỗ trợ đổi tên sheet, đổi tiêu đề và xóa cột.
     *
     * <p>HDSD: dùng thay cho
     * {@code TemplateExcelWriter.setSingleDataToTemplate(data, dynamicSheetName, dynamicTitle, removeColumns)}
     * khi không cần ẩn cột. Nếu không cần tham số nào, truyền {@code null} như cách code hiện tại đang dùng.</p>
     *
     * <p>Code mẫu:</p>
     * <pre>{@code
     * // Đầu vào: đổi tên sheet, đổi title và xóa cột index 3
     * File file = ExcelImportExportHelper.writeSingleTemplate(
     *     excelWriter,
     *     exportDto,
     *     "Danh sách",
     *     "DANH SÁCH VẬT TƯ",
     *     List.of(3)
     * );
     *
     * // Đầu ra:
     * // file = file Excel tạm đã ghi dữ liệu, đổi tên sheet/title và xóa cột theo cấu hình.
     * }</pre>
     *
     * @param excelWriter writer hiện có của project.
     * @param data DTO export/template.
     * @param dynamicSheetName tên sheet mới, có thể null.
     * @param dynamicTitle tiêu đề động ghi vào template, có thể null.
     * @param removeColumns danh sách index cột cần xóa, có thể null.
     * @param <T> kiểu DTO export/template.
     * @return file Excel tạm do {@link TemplateExcelWriter} sinh ra.
     */
    public static <T> File writeSingleTemplate(
            TemplateExcelWriter excelWriter,
            T data,
            String dynamicSheetName,
            String dynamicTitle,
            List<Integer> removeColumns
    ) {
        requireExcelWriter(excelWriter);
        return excelWriter.setSingleDataToTemplate(data, dynamicSheetName, dynamicTitle, removeColumns);
    }

    /**
     * Ghi dữ liệu vào template Excel một sheet, có hỗ trợ đổi tên sheet, đổi tiêu đề, xóa cột và ẩn cột.
     *
     * <p>HDSD: dùng thay cho
     * {@code TemplateExcelWriter.setSingleDataToTemplate(data, dynamicSheetName, dynamicTitle, columnsToRemove, columnsToHide)}.
     * Đây là overload đầy đủ nhất của nhóm single template.</p>
     *
     * <p>Code mẫu:</p>
     * <pre>{@code
     * // Đầu vào: xóa cột 4, ẩn cột 2
     * File file = ExcelImportExportHelper.writeSingleTemplate(
     *     excelWriter,
     *     exportDto,
     *     null,
     *     null,
     *     List.of(4),
     *     List.of(2)
     * );
     *
     * // Đầu ra:
     * // file = file Excel tạm đã ghi dữ liệu, xóa cột index 4 và ẩn cột index 2.
     * }</pre>
     *
     * @param excelWriter writer hiện có của project.
     * @param data DTO export/template.
     * @param dynamicSheetName tên sheet mới, có thể null.
     * @param dynamicTitle tiêu đề động ghi vào template, có thể null.
     * @param columnsToRemove danh sách index cột cần xóa, có thể null.
     * @param columnsToHide danh sách index cột cần ẩn, có thể null.
     * @param <T> kiểu DTO export/template.
     * @return file Excel tạm do {@link TemplateExcelWriter} sinh ra.
     */
    public static <T> File writeSingleTemplate(
            TemplateExcelWriter excelWriter,
            T data,
            String dynamicSheetName,
            String dynamicTitle,
            List<Integer> columnsToRemove,
            List<Integer> columnsToHide
    ) {
        requireExcelWriter(excelWriter);
        return excelWriter.setSingleDataToTemplate(data, dynamicSheetName, dynamicTitle, columnsToRemove, columnsToHide);
    }

    /**
     * Ghi dữ liệu vào template Excel nhiều sheet bằng {@link TemplateExcelWriter}.
     *
     * <p>HDSD: dùng thay cho
     * {@code TemplateExcelWriter.setSingleDataToTemplateMultiSheet(data, columnsToHide, dynamicTitle, removeCols)}
     * ở các case template có nhiều sheet hoặc DTO có nhiều list gắn {@code @ExcelExportForm}.</p>
     *
     * <p>Code mẫu:</p>
     * <pre>{@code
     * // Đầu vào: DTO export nhiều sheet, ẩn cột 1, set title động
     * File file = ExcelImportExportHelper.writeMultiSheetTemplate(
     *     excelWriter,
     *     exportDto,
     *     List.of(1),
     *     "DANH SÁCH IMPORT",
     *     null
     * );
     *
     * // Đầu ra:
     * // file = file Excel tạm đã ghi dữ liệu vào nhiều sheet theo annotation trong DTO.
     * }</pre>
     *
     * @param excelWriter writer hiện có của project.
     * @param data DTO export/template nhiều sheet.
     * @param columnsToHide danh sách index cột cần ẩn, có thể null.
     * @param dynamicTitle tiêu đề động ghi vào template, có thể null.
     * @param removeColumns danh sách index cột cần xóa, có thể null.
     * @param <T> kiểu DTO export/template.
     * @return file Excel tạm do {@link TemplateExcelWriter} sinh ra.
     */
    public static <T> File writeMultiSheetTemplate(
            TemplateExcelWriter excelWriter,
            T data,
            List<Integer> columnsToHide,
            String dynamicTitle,
            List<Integer> removeColumns
    ) {
        requireExcelWriter(excelWriter);
        return excelWriter.setSingleDataToTemplateMultiSheet(data, columnsToHide, dynamicTitle, removeColumns);
    }

    /**
     * Ghi dữ liệu vào các sheet cố định của template bằng {@link TemplateExcelWriter}.
     *
     * <p>HDSD: dùng thay cho {@code TemplateExcelWriter.setSingleDataToTemplateSheet(data)}
     * ở các case DTO có nhiều field list và mỗi field chỉ định sheet index bằng annotation.
     * Khác với {@link #writeMultiSheetTemplate(TemplateExcelWriter, Object, List, String, List)},
     * hàm này không nhận cấu hình ẩn/xóa cột hoặc title động.</p>
     *
     * <p>Code mẫu:</p>
     * <pre>{@code
     * // Đầu vào: exportDto có nhiều list tương ứng nhiều sheet cố định
     * File file = ExcelImportExportHelper.writeTemplateSheet(excelWriter, exportDto);
     *
     * // Đầu ra:
     * // file = file Excel tạm đã ghi dữ liệu vào các sheet theo annotation trong DTO.
     * }</pre>
     *
     * @param excelWriter writer hiện có của project.
     * @param data DTO export/template.
     * @param <T> kiểu DTO export/template.
     * @return file Excel tạm do {@link TemplateExcelWriter} sinh ra.
     */
    public static <T> File writeTemplateSheet(TemplateExcelWriter excelWriter, T data) {
        requireExcelWriter(excelWriter);
        return excelWriter.setSingleDataToTemplateSheet(data);
    }

    /**
     * Đọc file Excel tạm thành {@code byte[]} và xóa file sau khi đọc.
     *
     * <p>HDSD: dùng sau các hàm {@code TemplateExcelWriter.setSingleDataToTemplate...}
     * đang trả về {@link File}. Nếu xóa ngay không thành công, file sẽ được đánh dấu
     * {@code deleteOnExit()} để JVM dọn sau.</p>
     *
     * <p>Code mẫu:</p>
     * <pre>{@code
     * // Đầu vào: file tạm do TemplateExcelWriter sinh ra
     * File file = excelWriter.setSingleDataToTemplate(dto);
     * byte[] bytes = ExcelImportExportHelper.toBytesAndDelete(file);
     *
     * // Đầu ra:
     * // bytes = nội dung file Excel để trả về API download.
     * // file tạm được xóa sau khi đọc.
     * }</pre>
     *
     * @param file file Excel tạm vừa sinh từ template.
     * @return mảng byte để trả về API download.
     * @throws CustomException khi file null hoặc đọc file thất bại.
     */
    public static byte[] toBytesAndDelete(File file) {
        if (file == null) {
            throw new CustomException("File Excel không được null");
        }
        try {
            return Files.readAllBytes(file.toPath());
        } catch (IOException exception) {
            throw new CustomException("Đọc file Excel thất bại: " + exception.getMessage());
        } finally {
            if (file.exists() && !file.delete()) {
                file.deleteOnExit();
            }
        }
    }

    /**
     * Tạo HTTP headers cho API download Excel.
     *
     * <p>HDSD: dùng trong controller khi trả {@code ResponseEntity<byte[]>}. Ví dụ:
     * {@code ResponseEntity.ok().headers(downloadHeaders("Import.xlsx")).body(bytes)}.
     * Hàm mặc định content-type theo XLSX vì đa số template hiện tại dùng {@code XSSFWorkbook}.</p>
     *
     * <p>Code mẫu:</p>
     * <pre>{@code
     * // Đầu vào: fileName = "ImportDanhSach.xlsx"
     * HttpHeaders headers = ExcelImportExportHelper.downloadHeaders("ImportDanhSach.xlsx");
     *
     * // Đầu ra:
     * // Content-Disposition: attachment; filename="ImportDanhSach.xlsx"
     * // Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
     * }</pre>
     *
     * @param fileName tên file trả về client, nên gồm phần mở rộng {@code .xlsx}.
     * @return headers có {@code Content-Disposition} và {@code Content-Type}.
     */
    public static HttpHeaders downloadHeaders(String fileName) {
        return downloadHeaders(fileName, ExcelType.XLSX);
    }

    /**
     * Tạo HTTP headers cho API download Excel theo loại file cụ thể.
     *
     * <p>HDSD: dùng khi cần trả {@code .xls} hoặc loại Excel khác. Nếu {@code type}
     * không có media type tương ứng trong {@link ExcelType}, hàm vẫn tạo
     * {@code Content-Disposition} để browser tải file đúng tên.</p>
     *
     * <p>Code mẫu:</p>
     * <pre>{@code
     * // Đầu vào: fileName = "BaoCao.xls", type = ExcelType.XLS
     * HttpHeaders headers = ExcelImportExportHelper.downloadHeaders("BaoCao.xls", ExcelType.XLS);
     *
     * // Đầu ra:
     * // Content-Disposition: attachment; filename="BaoCao.xls"
     * // Content-Type: application/vnd.ms-excel
     * }</pre>
     *
     * @param fileName tên file trả về client.
     * @param type loại Excel dùng để set content-type.
     * @return headers có thông tin download.
     */
    public static HttpHeaders downloadHeaders(String fileName, ExcelType type) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment().filename(fileName).build());
        if (type != null && type.getMediaType() != null) {
            headers.setContentType(type.getMediaType());
        }
        return headers;
    }

    /**
     * Merge cell theo chiều ngang trên cùng một dòng.
     *
     * <p>HDSD: gọi khi cần gộp nhiều cột trong cùng một row, ví dụ tạo tiêu đề bảng:
     * {@code mergeHorizontal(sheet, 0, 0, 5)} sẽ merge vùng A1:F1. Hàm trả về
     * {@link CellRangeAddress} để caller có thể set border/style bổ sung nếu cần.</p>
     *
     * <p>Code mẫu:</p>
     * <pre>{@code
     * // Đầu vào: rowIndex = 0, firstCol = 0, lastCol = 5
     * CellRangeAddress region = ExcelImportExportHelper.mergeHorizontal(sheet, 0, 0, 5);
     *
     * // Đầu ra:
     * // Sheet được merge vùng A1:F1.
     * // region = CellRangeAddress(0, 0, 0, 5)
     * }</pre>
     *
     * @param sheet sheet cần thao tác.
     * @param rowIndex index dòng, bắt đầu từ 0.
     * @param firstCol index cột đầu, bắt đầu từ 0.
     * @param lastCol index cột cuối, bắt đầu từ 0.
     * @return vùng cell đã merge.
     */
    public static CellRangeAddress mergeHorizontal(Sheet sheet, int rowIndex, int firstCol, int lastCol) {
        return mergeRegion(sheet, rowIndex, rowIndex, firstCol, lastCol);
    }

    /**
     * Merge cell theo chiều dọc trên cùng một cột.
     *
     * <p>HDSD: gọi khi cần gộp nhiều dòng trong cùng một column, ví dụ ô STT hoặc nhóm dữ liệu:
     * {@code mergeVertical(sheet, 1, 4, 0)} sẽ merge vùng A2:A5. Hàm trả về
     * {@link CellRangeAddress} để caller có thể set border/style bổ sung nếu cần.</p>
     *
     * <p>Code mẫu:</p>
     * <pre>{@code
     * // Đầu vào: firstRow = 1, lastRow = 4, colIndex = 0
     * CellRangeAddress region = ExcelImportExportHelper.mergeVertical(sheet, 1, 4, 0);
     *
     * // Đầu ra:
     * // Sheet được merge vùng A2:A5.
     * // region = CellRangeAddress(1, 4, 0, 0)
     * }</pre>
     *
     * @param sheet sheet cần thao tác.
     * @param firstRow index dòng đầu, bắt đầu từ 0.
     * @param lastRow index dòng cuối, bắt đầu từ 0.
     * @param colIndex index cột cần merge, bắt đầu từ 0.
     * @return vùng cell đã merge.
     */
    public static CellRangeAddress mergeVertical(Sheet sheet, int firstRow, int lastRow, int colIndex) {
        return mergeRegion(sheet, firstRow, lastRow, colIndex, colIndex);
    }

    /**
     * Merge cell theo vùng bất kỳ, hỗ trợ cả merge ngang, merge dọc và merge khối.
     *
     * <p>HDSD: dùng khi caller đã có đủ 4 tọa độ vùng cần merge. Index dòng/cột bắt đầu từ 0.
     * Ví dụ {@code mergeRegion(sheet, 0, 1, 0, 3)} sẽ merge vùng A1:D2. Nếu chỉ cần merge ngang
     * hoặc dọc, ưu tiên dùng {@link #mergeHorizontal(Sheet, int, int, int)} hoặc
     * {@link #mergeVertical(Sheet, int, int, int)} để code dễ đọc hơn.</p>
     *
     * <p>Code mẫu:</p>
     * <pre>{@code
     * // Đầu vào: firstRow = 0, lastRow = 1, firstCol = 0, lastCol = 3
     * CellRangeAddress region = ExcelImportExportHelper.mergeRegion(sheet, 0, 1, 0, 3);
     *
     * // Đầu ra:
     * // Sheet được merge vùng A1:D2.
     * // region = CellRangeAddress(0, 1, 0, 3)
     * }</pre>
     *
     * @param sheet sheet cần thao tác.
     * @param firstRow index dòng đầu.
     * @param lastRow index dòng cuối.
     * @param firstCol index cột đầu.
     * @param lastCol index cột cuối.
     * @return vùng cell đã merge.
     * @throws CustomException khi vùng merge không hợp lệ.
     */
    public static CellRangeAddress mergeRegion(Sheet sheet, int firstRow, int lastRow, int firstCol, int lastCol) {
        validateMergeRegion(sheet, firstRow, lastRow, firstCol, lastCol);
        CellRangeAddress region = new CellRangeAddress(firstRow, lastRow, firstCol, lastCol);
        sheet.addMergedRegion(region);
        return region;
    }

    /**
     * Merge vùng cell và ghi giá trị vào ô góc trái trên của vùng merge.
     *
     * <p>HDSD: dùng cho tiêu đề hoặc nhãn nhóm khi vừa muốn merge vừa muốn set text/style.
     * Hàm chỉ ghi dữ liệu vào ô đầu vùng merge theo quy ước của Excel; các ô còn lại trong
     * vùng merge không cần set value. Nếu truyền {@code style}, style sẽ được gắn cho ô đầu.</p>
     *
     * <p>Code mẫu:</p>
     * <pre>{@code
     * // Đầu vào: merge A1:F1, value = "DANH SÁCH IMPORT", style = titleStyle
     * CellRangeAddress region = ExcelImportExportHelper.mergeRegionWithValue(
     *     sheet,
     *     0,
     *     0,
     *     0,
     *     5,
     *     "DANH SÁCH IMPORT",
     *     titleStyle
     * );
     *
     * // Đầu ra:
     * // Sheet được merge vùng A1:F1.
     * // Ô A1 có value = "DANH SÁCH IMPORT" và được set titleStyle.
     * // region = CellRangeAddress(0, 0, 0, 5)
     * }</pre>
     *
     * @param sheet sheet cần thao tác.
     * @param firstRow index dòng đầu.
     * @param lastRow index dòng cuối.
     * @param firstCol index cột đầu.
     * @param lastCol index cột cuối.
     * @param value giá trị ghi vào ô đầu vùng merge.
     * @param style style áp dụng cho ô đầu, có thể null.
     * @return vùng cell đã merge.
     */
    public static CellRangeAddress mergeRegionWithValue(
            Sheet sheet,
            int firstRow,
            int lastRow,
            int firstCol,
            int lastCol,
            Object value,
            CellStyle style
    ) {
        CellRangeAddress region = mergeRegion(sheet, firstRow, lastRow, firstCol, lastCol);
        Row row = getOrCreateRow(sheet, firstRow);
        Cell cell = getOrCreateCell(row, firstCol);
        setCellValue(cell, value);
        if (style != null) {
            cell.setCellStyle(style);
        }
        return region;
    }

    /**
     * Tạo cell header tại đúng vị trí dòng/cột, không merge.
     *
     * <p>HDSD: dùng khi cần ghi một header đơn lẻ vào sheet. Index dòng/cột bắt đầu từ 0.
     * Hàm tự tạo row/cell nếu chưa tồn tại, ghi value vào cell và set style nếu có.</p>
     *
     * <p>Code mẫu:</p>
     * <pre>{@code
     * // Đầu vào: rowIndex = 1, columnIndex = 2, value = "Mã tham số"
     * Cell headerCell = ExcelImportExportHelper.createHeader(
     *     sheet,
     *     1,
     *     2,
     *     "Mã tham số",
     *     headerStyle
     * );
     *
     * // Đầu ra:
     * // Ô C2 có value = "Mã tham số" và được set headerStyle.
     * // Không tạo vùng merge.
     * }</pre>
     *
     * @param sheet sheet cần tạo header.
     * @param rowIndex index dòng đặt header, bắt đầu từ 0.
     * @param columnIndex index cột đặt header, bắt đầu từ 0.
     * @param value giá trị hiển thị của header.
     * @param style style áp dụng cho cell header, có thể null.
     * @return cell header vừa tạo hoặc cập nhật.
     */
    public static Cell createHeader(Sheet sheet, int rowIndex, int columnIndex, Object value, CellStyle style) {
        validateCellPosition(sheet, rowIndex, columnIndex);
        Row row = getOrCreateRow(sheet, rowIndex);
        Cell cell = getOrCreateCell(row, columnIndex);
        setCellValue(cell, value);
        if (style != null) {
            cell.setCellStyle(style);
        }
        return cell;
    }

    /**
     * Tạo cell header tại đúng vị trí dòng/cột và merge theo vùng chỉ định.
     *
     * <p>HDSD: dùng khi header cần span nhiều cột/dòng. Vị trí {@code rowIndex}/{@code columnIndex}
     * phải là ô góc trái trên của vùng merge, tức bằng {@code mergeFirstRow}/{@code mergeFirstCol},
     * vì Excel chỉ hiển thị value của ô đầu vùng merge. Index dòng/cột bắt đầu từ 0.</p>
     *
     * <p>Code mẫu:</p>
     * <pre>{@code
     * // Đầu vào: tạo header tại B1, merge từ B1 đến E1
     * Cell headerCell = ExcelImportExportHelper.createHeader(
     *     sheet,
     *     0,
     *     1,
     *     "Thông tin chung",
     *     headerStyle,
     *     0,
     *     0,
     *     1,
     *     4
     * );
     *
     * // Đầu ra:
     * // Sheet được merge vùng B1:E1.
     * // Ô B1 có value = "Thông tin chung" và được set headerStyle.
     * }</pre>
     *
     * @param sheet sheet cần tạo header.
     * @param rowIndex index dòng đặt header, bắt đầu từ 0.
     * @param columnIndex index cột đặt header, bắt đầu từ 0.
     * @param value giá trị hiển thị của header.
     * @param style style áp dụng cho cell header, có thể null.
     * @param mergeFirstRow dòng bắt đầu vùng merge.
     * @param mergeLastRow dòng kết thúc vùng merge.
     * @param mergeFirstCol cột bắt đầu vùng merge.
     * @param mergeLastCol cột kết thúc vùng merge.
     * @return cell header vừa tạo hoặc cập nhật.
     * @throws CustomException khi vị trí header không phải ô đầu vùng merge hoặc vùng merge không hợp lệ.
     */
    public static Cell createHeader(
            Sheet sheet,
            int rowIndex,
            int columnIndex,
            Object value,
            CellStyle style,
            int mergeFirstRow,
            int mergeLastRow,
            int mergeFirstCol,
            int mergeLastCol
    ) {
        if (rowIndex != mergeFirstRow || columnIndex != mergeFirstCol) {
            throw new CustomException("Vị trí header phải là ô góc trái trên của vùng merge");
        }
        Cell cell = createHeader(sheet, rowIndex, columnIndex, value, style);
        mergeRegion(sheet, mergeFirstRow, mergeLastRow, mergeFirstCol, mergeLastCol);
        return cell;
    }

    /**
     * Tạo hoặc cập nhật một cell bất kỳ tại đúng vị trí dòng/cột.
     *
     * <p>HDSD: dùng khi cần ghi dữ liệu thủ công vào sheet, không phụ thuộc template annotation.
     * Index dòng/cột bắt đầu từ 0. Hàm tự tạo row/cell nếu chưa tồn tại, ghi value theo kiểu
     * dữ liệu cơ bản và set style nếu có.</p>
     *
     * <p>Code mẫu:</p>
     * <pre>{@code
     * // Đầu vào: rowIndex = 2, columnIndex = 3, value = 15
     * Cell cell = ExcelImportExportHelper.createCell(sheet, 2, 3, 15, bodyStyle);
     *
     * // Đầu ra:
     * // Ô D3 có value = 15 và được set bodyStyle.
     * }</pre>
     *
     * @param sheet sheet cần ghi dữ liệu.
     * @param rowIndex index dòng, bắt đầu từ 0.
     * @param columnIndex index cột, bắt đầu từ 0.
     * @param value giá trị cần ghi vào cell.
     * @param style style áp dụng cho cell, có thể null.
     * @return cell vừa tạo hoặc cập nhật.
     */
    public static Cell createCell(Sheet sheet, int rowIndex, int columnIndex, Object value, CellStyle style) {
        validateCellPosition(sheet, rowIndex, columnIndex);
        Row row = getOrCreateRow(sheet, rowIndex);
        Cell cell = getOrCreateCell(row, columnIndex);
        setCellValue(cell, value);
        if (style != null) {
            cell.setCellStyle(style);
        }
        return cell;
    }

    /**
     * Ghi một dòng dữ liệu từ danh sách value, bắt đầu tại cột chỉ định.
     *
     * <p>HDSD: dùng khi cần ghi body row thủ công. Ví dụ ghi mã, tên, trạng thái theo thứ tự
     * từ cột B thì truyền {@code startColumnIndex = 1}. Hàm trả về row để caller có thể set
     * height hoặc thao tác bổ sung nếu cần.</p>
     *
     * <p>Code mẫu:</p>
     * <pre>{@code
     * // Đầu vào: ghi row 5, bắt đầu từ cột B
     * Row row = ExcelImportExportHelper.createRowCells(
     *     sheet,
     *     4,
     *     1,
     *     List.of("A001", "Tên A", true),
     *     bodyStyle
     * );
     *
     * // Đầu ra:
     * // B5 = "A001", C5 = "Tên A", D5 = true; các cell được set bodyStyle.
     * }</pre>
     *
     * @param sheet sheet cần ghi dữ liệu.
     * @param rowIndex index dòng, bắt đầu từ 0.
     * @param startColumnIndex index cột bắt đầu ghi, bắt đầu từ 0.
     * @param values danh sách giá trị ghi theo thứ tự từ trái sang phải.
     * @param style style áp dụng cho từng cell, có thể null.
     * @return row vừa tạo hoặc cập nhật.
     */
    public static Row createRowCells(
            Sheet sheet,
            int rowIndex,
            int startColumnIndex,
            List<?> values,
            CellStyle style
    ) {
        validateCellPosition(sheet, rowIndex, startColumnIndex);
        Row row = getOrCreateRow(sheet, rowIndex);
        if (values == null || values.isEmpty()) {
            return row;
        }
        for (int i = 0; i < values.size(); i++) {
            Cell cell = getOrCreateCell(row, startColumnIndex + i);
            setCellValue(cell, values.get(i));
            if (style != null) {
                cell.setCellStyle(style);
            }
        }
        return row;
    }

    /**
     * Tự động căn độ rộng các cột trong khoảng chỉ định theo nội dung cell.
     *
     * <p>HDSD: gọi sau khi đã ghi xong dữ liệu vào sheet. Index cột bắt đầu từ 0.
     * Hàm chạy {@code sheet.autoSizeColumn(columnIndex)} cho từng cột trong khoảng
     * {@code fromColumnIndex..toColumnIndex}.</p>
     *
     * <p>Code mẫu:</p>
     * <pre>{@code
     * // Đầu vào: auto-size từ cột A đến cột D
     * ExcelImportExportHelper.autoSizeColumns(sheet, 0, 3);
     *
     * // Đầu ra:
     * // Các cột A:D được tự căn width theo nội dung hiện có trong sheet.
     * }</pre>
     *
     * @param sheet sheet cần auto-size.
     * @param fromColumnIndex cột bắt đầu, bắt đầu từ 0.
     * @param toColumnIndex cột kết thúc, bắt đầu từ 0.
     * @throws CustomException khi khoảng cột không hợp lệ.
     */
    public static void autoSizeColumns(Sheet sheet, int fromColumnIndex, int toColumnIndex) {
        validateColumnRange(sheet, fromColumnIndex, toColumnIndex);
        for (int columnIndex = fromColumnIndex; columnIndex <= toColumnIndex; columnIndex++) {
            sheet.autoSizeColumn(columnIndex);
        }
    }

    /**
     * Áp dụng một style cho toàn bộ cell trong vùng chỉ định.
     *
     * <p>HDSD: dùng sau khi merge hoặc sau khi tạo bảng để set border/alignment/background
     * đồng nhất cho cả vùng. Hàm tự tạo row/cell còn thiếu trong vùng để đảm bảo style được
     * apply đủ các ô.</p>
     *
     * <p>Code mẫu:</p>
     * <pre>{@code
     * // Đầu vào: style vùng A1:D2
     * ExcelImportExportHelper.applyStyleToRegion(sheet, 0, 1, 0, 3, headerStyle);
     *
     * // Đầu ra:
     * // Tất cả cell trong A1:D2 được set headerStyle.
     * }</pre>
     *
     * @param sheet sheet cần thao tác.
     * @param firstRow dòng bắt đầu vùng style.
     * @param lastRow dòng kết thúc vùng style.
     * @param firstCol cột bắt đầu vùng style.
     * @param lastCol cột kết thúc vùng style.
     * @param style style áp dụng cho vùng.
     * @throws CustomException khi vùng style không hợp lệ hoặc style null.
     */
    public static void applyStyleToRegion(
            Sheet sheet,
            int firstRow,
            int lastRow,
            int firstCol,
            int lastCol,
            CellStyle style
    ) {
        if (style == null) {
            throw new CustomException("CellStyle không được null");
        }
        validateCellRegion(sheet, firstRow, lastRow, firstCol, lastCol);
        for (int rowIndex = firstRow; rowIndex <= lastRow; rowIndex++) {
            Row row = getOrCreateRow(sheet, rowIndex);
            for (int columnIndex = firstCol; columnIndex <= lastCol; columnIndex++) {
                getOrCreateCell(row, columnIndex).setCellStyle(style);
            }
        }
    }

    /**
     * Gắn dropdown Excel dạng công thức list vào một vùng cell trên sheet XSSF.
     *
     * <p>HDSD: dùng khi caller đã có sẵn formula trỏ tới vùng dữ liệu danh mục, named range
     * hoặc công thức {@code INDIRECT}. Index dòng/cột bắt đầu từ 0. Đây là primitive chung cho
     * các hàm tạo dropdown danh mục bên dưới.</p>
     *
     * <p>Code mẫu:</p>
     * <pre>{@code
     * // Đầu vào: dropdown cho cột D, từ dòng 2 đến dòng 101, lấy dữ liệu từ Ref!B1:B20
     * ExcelImportExportHelper.setXssfListValidation(
     *     importSheet,
     *     "Ref!$B$1:$B$20",
     *     1,
     *     100,
     *     3,
     *     3
     * );
     *
     * // Đầu ra:
     * // Các ô D2:D101 có dropdown lấy danh sách từ Ref!B1:B20.
     * }</pre>
     *
     * @param sheet sheet cần gắn dropdown.
     * @param formulaString công thức list validation, ví dụ {@code Ref!$B$1:$B$20} hoặc named range.
     * @param firstRow dòng bắt đầu vùng nhập, index 0.
     * @param lastRow dòng kết thúc vùng nhập, index 0.
     * @param firstCol cột bắt đầu vùng nhập, index 0.
     * @param lastCol cột kết thúc vùng nhập, index 0.
     * @throws CustomException khi sheet/formula/range không hợp lệ.
     */
    public static void setXssfListValidation(
            XSSFSheet sheet,
            String formulaString,
            int firstRow,
            int lastRow,
            int firstCol,
            int lastCol
    ) {
        validateFormulaValidationInput(sheet, formulaString, firstRow, lastRow, firstCol, lastCol);
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

    /**
     * Ghi dữ liệu danh mục vào sheet nguồn theo thứ tự key truyền vào.
     *
     * <p>HDSD: dùng để chuẩn bị sheet ẩn chứa danh mục trước khi gắn dropdown. Mỗi phần tử
     * trong {@code categories} là một dòng; mỗi key trong {@code keyList} là một cột. Thường
     * quy ước cột A là mã/id, cột B là tên hiển thị cho dropdown.</p>
     *
     * <p>Code mẫu:</p>
     * <pre>{@code
     * // Đầu vào:
     * // categories = [{code="A001", name="Phân bón"}]
     * // keyList = ["code", "name"]
     * ExcelImportExportHelper.writeCategorySheet(refSheet, categories, List.of("code", "name"));
     *
     * // Đầu ra:
     * // Ref!A1 = "A001", Ref!B1 = "Phân bón".
     * }</pre>
     *
     * @param sheet sheet nguồn cần ghi danh mục.
     * @param categories danh sách danh mục.
     * @param keyList thứ tự key cần ghi ra cột.
     * @throws CustomException khi sheet/keyList không hợp lệ.
     */
    public static void writeCategorySheet(
            XSSFSheet sheet,
            List<Map<String, Object>> categories,
            List<String> keyList
    ) {
        if (sheet == null) {
            throw new CustomException("Sheet danh mục không được null");
        }
        if (keyList == null || keyList.isEmpty()) {
            throw new CustomException("Danh sách key danh mục không được rỗng");
        }
        if (categories == null || categories.isEmpty()) {
            return;
        }
        int dataRowStart = 0;
        for (Map<String, Object> category : categories) {
            AtomicInteger colStart = new AtomicInteger(0);
            Row row = getOrCreateRow(sheet, dataRowStart);
            keyList.forEach(key -> {
                Cell cell = getOrCreateCell(row, colStart.getAndIncrement());
                setCellValue(cell, category == null ? null : category.get(key));
            });
            dataRowStart++;
        }
    }

    /**
     * Tạo sheet danh mục ẩn và gắn dropdown danh mục đơn giản vào cột đích.
     *
     * <p>HDSD: dùng cho danh mục không phụ thuộc cột khác. Hàm ghi {@code categories} vào
     * {@code srcSheet}, lấy cột B của sheet đó làm label hiển thị, gắn dropdown vào
     * {@code desSheet}, sau đó ẩn và protect sheet danh mục nếu có mật khẩu.</p>
     *
     * <p>Code mẫu:</p>
     * <pre>{@code
     * // Đầu vào: dropdown cột C, dòng 2..101, nguồn danh mục có code/name
     * ExcelImportExportHelper.createCategorySheet(
     *     workbook,
     *     refSheet,
     *     importSheet,
     *     categories,
     *     List.of("code", "name"),
     *     1,
     *     100,
     *     2,
     *     2,
     *     "123"
     * );
     *
     * // Đầu ra:
     * // refSheet được ghi dữ liệu và ẩn; C2:C101 trên importSheet có dropdown theo refSheet cột B.
     * }</pre>
     *
     * @param wb workbook chứa source/destination sheet.
     * @param srcSheet sheet nguồn chứa danh mục.
     * @param desSheet sheet đích người dùng nhập dữ liệu.
     * @param categories danh sách danh mục.
     * @param keyList thứ tự key ghi vào sheet nguồn.
     * @param firstRow dòng bắt đầu vùng dropdown, index 0.
     * @param lastRow dòng kết thúc vùng dropdown, index 0.
     * @param firstCol cột bắt đầu vùng dropdown, index 0.
     * @param lastCol cột kết thúc vùng dropdown, index 0.
     * @param pass mật khẩu protect sheet nguồn, có thể null.
     */
    public static void createCategorySheet(
            XSSFWorkbook wb,
            XSSFSheet srcSheet,
            XSSFSheet desSheet,
            List<Map<String, Object>> categories,
            List<String> keyList,
            int firstRow,
            int lastRow,
            int firstCol,
            int lastCol,
            String pass
    ) {
        if (categories == null || categories.isEmpty()) {
            return;
        }
        validateWorkbookAndSheets(wb, srcSheet, desSheet);
        writeCategorySheet(srcSheet, categories, keyList);
        setXssfListValidation(desSheet, srcSheet.getSheetName() + "!$B$1:$B$" + categories.size(),
                firstRow, lastRow, firstCol, lastCol);
        hideAndProtectSheet(wb, srcSheet, pass);
    }

    /**
     * Tạo sheet danh mục ẩn và gắn dropdown đơn giản bằng cấu hình map giống {@link TemplateExcelWriter}.
     *
     * <p>HDSD: dùng khi muốn chuyển dần call site cũ từ {@code TemplateExcelWriter.createCategorySheet}
     * sang helper mà không đổi cấu trúc tham số. {@code params} cần có {@code keyList},
     * {@code firstRow}, {@code lastRow}, {@code firstCol}, {@code lastCol}; {@code pass} và
     * {@code isGenerateData} là tùy chọn.</p>
     *
     * <p>Code mẫu:</p>
     * <pre>{@code
     * // Đầu vào: params chứa keyList, firstRow/lastRow/firstCol/lastCol
     * ExcelImportExportHelper.createCategorySheet(workbook, refSheet, importSheet, categories, params);
     *
     * // Đầu ra:
     * // Sheet danh mục được ghi/ẩn; cột đích có dropdown theo cột B của sheet danh mục.
     * }</pre>
     *
     * @param wb workbook chứa source/destination sheet.
     * @param srcSheet sheet nguồn chứa danh mục.
     * @param desSheet sheet đích người dùng nhập dữ liệu.
     * @param categories danh sách danh mục.
     * @param params cấu hình range và key danh mục.
     */
    public static void createCategorySheet(
            XSSFWorkbook wb,
            XSSFSheet srcSheet,
            XSSFSheet desSheet,
            List<Map<String, Object>> categories,
            Map<String, Object> params
    ) {
        if (categories == null || categories.isEmpty()) {
            return;
        }
        validateWorkbookAndSheets(wb, srcSheet, desSheet);
        List<String> keyList = getStringListParam(params, "keyList");
        if (getBooleanParam(params, "isGenerateData", true)) {
            writeCategorySheet(srcSheet, categories, keyList);
        }
        setXssfListValidation(desSheet, srcSheet.getSheetName() + "!$B$1:$B$" + categories.size(),
                getIntParam(params, "firstRow"),
                getIntParam(params, "lastRow"),
                getIntParam(params, "firstCol"),
                getIntParam(params, "lastCol"));
        hideAndProtectSheet(wb, srcSheet, getStringParam(params, "pass", null));
    }

    /**
     * Tạo dropdown danh mục phụ thuộc danh mục cha bằng công thức {@code INDIRECT}.
     *
     * <p>HDSD: dùng cho case cột con phụ thuộc giá trị đã chọn ở cột cha. Hàm ghi các danh mục con
     * vào sheet nguồn, tạo named range dạng {@code srcSheetName_parentId}, rồi gắn validation cho
     * cột con trên sheet đích. {@code params} tương thích với hàm cũ trong {@link TemplateExcelWriter}:
     * cần {@code keyList}, {@code refSheetName}, {@code firstRow}, {@code lastRow},
     * {@code firstCol}, {@code lastCol}; có thể truyền thêm {@code refParentCode},
     * {@code refCode}, {@code distanceToRef}, {@code pass}, {@code isGenerateData}.</p>
     *
     * <p>Code mẫu:</p>
     * <pre>{@code
     * // Đầu vào:
     * // refCategories = tỉnh; categories = xã có parentCode trỏ code tỉnh
     * ExcelImportExportHelper.createCategorySheetWithReference(
     *     workbook,
     *     communeSheet,
     *     importSheet,
     *     communes,
     *     provinces,
     *     params
     * );
     *
     * // Đầu ra:
     * // Cột xã trên importSheet có dropdown thay đổi theo tỉnh đã chọn ở cột tham chiếu.
     * }</pre>
     *
     * @param wb workbook chứa các sheet.
     * @param srcSheet sheet nguồn chứa danh mục con.
     * @param desSheet sheet đích người dùng nhập dữ liệu.
     * @param categories danh sách danh mục con.
     * @param refCategories danh sách danh mục cha.
     * @param params cấu hình key/range/reference.
     */
    public static void createCategorySheetWithReference(
            XSSFWorkbook wb,
            XSSFSheet srcSheet,
            XSSFSheet desSheet,
            List<Map<String, Object>> categories,
            List<Map<String, Object>> refCategories,
            Map<String, Object> params
    ) {
        createCategorySheetWithReferenceInternal(wb, srcSheet, desSheet, categories, refCategories, params, false);
    }

    /**
     * Tạo dropdown danh mục phụ thuộc danh mục cha, map theo code thay vì name.
     *
     * <p>HDSD: dùng cho case tên danh mục cha có thể trùng nhau nên không nên dò theo label.
     * Hàm tạo công thức phụ thuộc cột code kế bên theo logic cũ của
     * {@code TemplateExcelWriter.createCategorySheetWithReferenceCode}.</p>
     *
     * <p>Code mẫu:</p>
     * <pre>{@code
     * // Đầu vào: danh mục thôn/xã cần map theo code xã thay vì tên xã
     * ExcelImportExportHelper.createCategorySheetWithReferenceCode(
     *     workbook,
     *     villageSheet,
     *     importSheet,
     *     villages,
     *     communes,
     *     params
     * );
     *
     * // Đầu ra:
     * // Cột thôn trên importSheet có dropdown theo code xã, giảm lỗi khi tên bị trùng.
     * }</pre>
     *
     * @param wb workbook chứa các sheet.
     * @param srcSheet sheet nguồn chứa danh mục con.
     * @param desSheet sheet đích người dùng nhập dữ liệu.
     * @param categories danh sách danh mục con.
     * @param refCategories danh sách danh mục cha.
     * @param params cấu hình key/range/reference.
     */
    public static void createCategorySheetWithReferenceCode(
            XSSFWorkbook wb,
            XSSFSheet srcSheet,
            XSSFSheet desSheet,
            List<Map<String, Object>> categories,
            List<Map<String, Object>> refCategories,
            Map<String, Object> params
    ) {
        createCategorySheetWithReferenceInternal(wb, srcSheet, desSheet, categories, refCategories, params, true);
    }

    /**
     * Render dropdown ref chuẩn kiểu dynamic-import: sheet ref ẩn + named range + validation.
     *
     * <p>HDSD: dùng khi đã query được danh sách ref từ DB và muốn tạo dropdown độc lập với
     * {@code TemplateBuilderService}. Hàm tạo sheet ref có 2 cột {@code value}/{@code label},
     * ghi dữ liệu từ {@code rows}, tạo named range trỏ tới cột label, ẩn sheet ref và gắn
     * dropdown vào cột đích của {@code targetSheet}.</p>
     *
     * <p>Code mẫu:</p>
     * <pre>{@code
     * // Đầu vào: rows = [{uuid=1, name="Lúa"}], dropdown cột C từ dòng 2..1001
     * ExcelImportExportHelper.renderStandardRefDropdown(
     *     workbook,
     *     importSheet,
     *     "Ref_crop",
     *     "Ref_crop",
     *     rows,
     *     "uuid",
     *     "name",
     *     2,
     *     1,
     *     1000
     * );
     *
     * // Đầu ra:
     * // Sheet Ref_crop bị ẩn, named range Ref_crop trỏ tới label, cột C có dropdown tên cây trồng.
     * }</pre>
     *
     * @param wb workbook đang build.
     * @param targetSheet sheet đích người dùng nhập dữ liệu.
     * @param refSheetName tên sheet ref sẽ tạo.
     * @param namedRangeName tên named range dùng trong validation.
     * @param rows danh sách ref đã query.
     * @param valueKey key lấy giá trị lưu DB.
     * @param labelKey key lấy label hiển thị dropdown.
     * @param targetColIdx cột đích gắn dropdown, index 0.
     * @param firstDataRow dòng bắt đầu gắn dropdown, index 0.
     * @param lastDataRow dòng kết thúc gắn dropdown, index 0.
     */
    public static void renderStandardRefDropdown(
            XSSFWorkbook wb,
            XSSFSheet targetSheet,
            String refSheetName,
            String namedRangeName,
            List<Map<String, Object>> rows,
            String valueKey,
            String labelKey,
            int targetColIdx,
            int firstDataRow,
            int lastDataRow
    ) {
        if (wb == null) {
            throw new CustomException("Workbook không được null");
        }
        if (targetSheet == null) {
            throw new CustomException("Sheet đích không được null");
        }
        if (refSheetName == null || refSheetName.isBlank()) {
            throw new CustomException("Tên sheet ref không được rỗng");
        }
        if (namedRangeName == null || namedRangeName.isBlank()) {
            throw new CustomException("Tên named range không được rỗng");
        }
        if (valueKey == null || valueKey.isBlank() || labelKey == null || labelKey.isBlank()) {
            throw new CustomException("Key value/label không được rỗng");
        }
        validateCellRegion(targetSheet, firstDataRow, lastDataRow, targetColIdx, targetColIdx);
        if (wb.getSheet(refSheetName) != null) {
            throw new CustomException("Sheet ref đã tồn tại: " + refSheetName);
        }
        if (wb.getName(namedRangeName) != null) {
            throw new CustomException("Named range đã tồn tại: " + namedRangeName);
        }

        List<Map<String, Object>> safeRows = rows == null ? List.of() : rows;
        XSSFSheet refSheet = wb.createSheet(refSheetName);
        Row headerRow = refSheet.createRow(0);
        headerRow.createCell(0).setCellValue("value");
        headerRow.createCell(1).setCellValue("label");
        for (int rowIndex = 0; rowIndex < safeRows.size(); rowIndex++) {
            Map<String, Object> rowData = safeRows.get(rowIndex);
            Row row = refSheet.createRow(rowIndex + 1);
            row.createCell(0).setCellValue(stringValue(rowData == null ? null : rowData.get(valueKey)));
            row.createCell(1).setCellValue(stringValue(rowData == null ? null : rowData.get(labelKey)));
        }
        wb.setSheetHidden(wb.getSheetIndex(refSheet), true);

        int lastRefRow = Math.max(safeRows.size(), 1) + 1;
        XSSFName name = wb.createName();
        name.setNameName(namedRangeName);
        name.setRefersToFormula(String.format("'%s'!$B$2:$B$%d", refSheetName, lastRefRow));
        setXssfListValidation(targetSheet, namedRangeName, firstDataRow, lastDataRow, targetColIdx, targetColIdx);
    }

    private static boolean hasExcelExtension(String fileName) {
        String lowerFileName = fileName.toLowerCase();
        return lowerFileName.endsWith(XLS_EXTENSION) || lowerFileName.endsWith(XLSX_EXTENSION);
    }

    private static void requireExcelWriter(TemplateExcelWriter excelWriter) {
        if (excelWriter == null) {
            throw new CustomException("TemplateExcelWriter không được null");
        }
    }

    private static void createCategorySheetWithReferenceInternal(
            XSSFWorkbook wb,
            XSSFSheet srcSheet,
            XSSFSheet desSheet,
            List<Map<String, Object>> categories,
            List<Map<String, Object>> refCategories,
            Map<String, Object> params,
            boolean referenceByCode
    ) {
        if (categories == null || categories.isEmpty()) {
            return;
        }
        validateWorkbookAndSheets(wb, srcSheet, desSheet);
        if (refCategories == null || refCategories.isEmpty()) {
            return;
        }
        List<String> keyList = getStringListParam(params, "keyList");
        String pass = getStringParam(params, "pass", null);
        String refParentCode = getStringParam(params, "refParentCode", "parentCode");
        String refCode = getStringParam(params, "refCode", "code");
        Integer distanceToRef = getIntParam(params, "distanceToRef", 1);
        String refSheetName = getStringParam(params, "refSheetName", null);
        String srcSheetName = srcSheet.getSheetName();

        if (getBooleanParam(params, "isGenerateData", true)) {
            AtomicInteger dataRowStart = new AtomicInteger(0);
            for (Map<String, Object> ref : refCategories) {
                Object refValue = ref == null ? null : ref.get(refCode);
                List<Map<String, Object>> childCategories = categories.stream()
                        .filter(category -> Objects.equals(
                                stringValue(category == null ? null : category.get(refParentCode)),
                                stringValue(refValue)
                        ))
                        .toList();
                int initRowStart = dataRowStart.get() + 1;
                childCategories.forEach(category -> {
                    AtomicInteger colStart = new AtomicInteger(0);
                    Row row = getOrCreateRow(srcSheet, dataRowStart.get());
                    keyList.forEach(key -> {
                        Cell cell = getOrCreateCell(row, colStart.getAndIncrement());
                        setCellValue(cell, category == null ? null : category.get(key));
                    });
                    dataRowStart.getAndIncrement();
                });

                XSSFName name = wb.createName();
                name.setNameName(srcSheetName + '_' + stringValue(firstNonNull(
                        ref == null ? null : ref.get("id"),
                        ref == null ? null : ref.get("code")
                )));
                name.setRefersToFormula(srcSheetName + "!$B$" + initRowStart + ":$B$" + dataRowStart.get());
            }
        }

        String refFormulas;
        if (referenceByCode) {
            refFormulas = "INDEX(" + refSheetName + "!$A$1:$A$" + refCategories.size()
                    + ",MATCH(INDIRECT(ADDRESS(ROW(),COLUMN()+1))," + refSheetName + "!$A$1:$A$"
                    + refCategories.size() + ",0))";
        } else if (getBooleanParam(params, "isExtendRef", false)) {
            String importSheetName = getStringParam(params, "importSheetName", "import");
            Integer firstRow = getIntParam(params, "firstRow", 5);
            Integer distanceRef = getIntParam(params, "distanceRef", 2);
            refFormulas = "INDIRECT(\"" + importSheetName + "!\"&ADDRESS(ROW()-" + firstRow
                    + ",COLUMN()-" + distanceRef + "))";
        } else {
            if (refSheetName == null || refSheetName.isBlank()) {
                throw new CustomException("refSheetName không được rỗng");
            }
            refFormulas = "INDEX(" + refSheetName + "!$A$1:$A$" + refCategories.size()
                    + ",MATCH(INDIRECT(ADDRESS(ROW(),COLUMN()-" + distanceToRef + ")),"
                    + refSheetName + "!$B$1:$B$" + refCategories.size() + ",0))";
        }

        String formulas = "INDIRECT(\"" + srcSheetName + "_\"&" + refFormulas + ")";
        setXssfListValidation(desSheet, formulas,
                getIntParam(params, "firstRow"),
                getIntParam(params, "lastRow"),
                getIntParam(params, "firstCol"),
                getIntParam(params, "lastCol"));
        hideAndProtectSheet(wb, srcSheet, pass);
    }

    private static void validateWorkbookAndSheets(XSSFWorkbook wb, XSSFSheet srcSheet, XSSFSheet desSheet) {
        if (wb == null) {
            throw new CustomException("Workbook không được null");
        }
        if (srcSheet == null) {
            throw new CustomException("Sheet danh mục không được null");
        }
        if (desSheet == null) {
            throw new CustomException("Sheet đích không được null");
        }
    }

    private static void hideAndProtectSheet(XSSFWorkbook wb, XSSFSheet sheet, String pass) {
        wb.setSheetHidden(wb.getSheetIndex(sheet), true);
        if (pass != null && !pass.isBlank()) {
            sheet.protectSheet(pass);
        }
    }

    private static void validateFormulaValidationInput(
            XSSFSheet sheet,
            String formulaString,
            int firstRow,
            int lastRow,
            int firstCol,
            int lastCol
    ) {
        if (formulaString == null || formulaString.isBlank()) {
            throw new CustomException("Formula dropdown không được rỗng");
        }
        validateCellRegion(sheet, firstRow, lastRow, firstCol, lastCol);
    }

    @SuppressWarnings("unchecked")
    private static List<String> getStringListParam(Map<String, Object> params, String key) {
        if (params == null || !(params.get(key) instanceof List<?> values) || values.isEmpty()) {
            throw new CustomException("Thiếu cấu hình " + key);
        }
        return (List<String>) values;
    }

    private static int getIntParam(Map<String, Object> params, String key) {
        Object value = params == null ? null : params.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new CustomException("Thiếu cấu hình số " + key);
    }

    private static int getIntParam(Map<String, Object> params, String key, int defaultValue) {
        Object value = params == null ? null : params.get(key);
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    private static boolean getBooleanParam(Map<String, Object> params, String key, boolean defaultValue) {
        Object value = params == null ? null : params.get(key);
        return value instanceof Boolean bool ? bool : defaultValue;
    }

    private static String getStringParam(Map<String, Object> params, String key, String defaultValue) {
        Object value = params == null ? null : params.get(key);
        return value == null ? defaultValue : value.toString();
    }

    private static Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private static void validateCellPosition(Sheet sheet, int rowIndex, int columnIndex) {
        if (sheet == null) {
            throw new CustomException("Sheet không được null");
        }
        if (rowIndex < 0 || columnIndex < 0) {
            throw new CustomException("Index dòng/cột phải lớn hơn hoặc bằng 0");
        }
    }

    private static void validateColumnRange(Sheet sheet, int fromColumnIndex, int toColumnIndex) {
        if (sheet == null) {
            throw new CustomException("Sheet không được null");
        }
        if (fromColumnIndex < 0 || toColumnIndex < 0) {
            throw new CustomException("Index cột phải lớn hơn hoặc bằng 0");
        }
        if (fromColumnIndex > toColumnIndex) {
            throw new CustomException("Cột bắt đầu không được lớn hơn cột kết thúc");
        }
    }

    private static void validateCellRegion(Sheet sheet, int firstRow, int lastRow, int firstCol, int lastCol) {
        if (sheet == null) {
            throw new CustomException("Sheet không được null");
        }
        if (firstRow < 0 || lastRow < 0 || firstCol < 0 || lastCol < 0) {
            throw new CustomException("Index dòng/cột phải lớn hơn hoặc bằng 0");
        }
        if (firstRow > lastRow) {
            throw new CustomException("Dòng bắt đầu không được lớn hơn dòng kết thúc");
        }
        if (firstCol > lastCol) {
            throw new CustomException("Cột bắt đầu không được lớn hơn cột kết thúc");
        }
    }

    private static void validateMergeRegion(Sheet sheet, int firstRow, int lastRow, int firstCol, int lastCol) {
        if (sheet == null) {
            throw new CustomException("Sheet không được null");
        }
        if (firstRow < 0 || lastRow < 0 || firstCol < 0 || lastCol < 0) {
            throw new CustomException("Index dòng/cột merge phải lớn hơn hoặc bằng 0");
        }
        if (firstRow > lastRow) {
            throw new CustomException("Dòng bắt đầu merge không được lớn hơn dòng kết thúc");
        }
        if (firstCol > lastCol) {
            throw new CustomException("Cột bắt đầu merge không được lớn hơn cột kết thúc");
        }
        if (firstRow == lastRow && firstCol == lastCol) {
            throw new CustomException("Vùng merge phải có ít nhất 2 ô");
        }
    }

    private static Row getOrCreateRow(Sheet sheet, int rowIndex) {
        Row row = sheet.getRow(rowIndex);
        return row != null ? row : sheet.createRow(rowIndex);
    }

    private static Cell getOrCreateCell(Row row, int cellIndex) {
        Cell cell = row.getCell(cellIndex);
        return cell != null ? cell : row.createCell(cellIndex);
    }

    private static void setCellValue(Cell cell, Object value) {
        if (value == null) {
            cell.setBlank();
        } else if (value instanceof Number number) {
            cell.setCellValue(number.doubleValue());
        } else if (value instanceof Boolean bool) {
            cell.setCellValue(bool);
        } else {
            cell.setCellValue(value.toString());
        }
    }
}
