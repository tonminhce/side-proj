package vn.vnpt.util.common.constant;

public interface CommonConstant {
  String EXCEL_TEMPLATE_FOLDER = "templates/excel/";
  String DOCX_TEMPLATE_FOLDER = "templates/docx/";
  String PDF_TEMPLATE_FOLDER = "templates/pdf/";
  String IMPORT_EXCEL_DATE_FORMAT = "yyyy/MM/dd";
  String EXCEL = "application/vnd.ms-excel";
  String EXCELXLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
  String TELEGRAM_TEMPORARY_FOLDER = "temporary_folder/telegram/";

  final class MimeType {
    public static final String EXCEL = "application/vnd.ms-excel";
    public static final String PDF = "application/pdf";
    public static final String EXCELXLSX =
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    public static final String PNG = "image/png";
    public static final String JPEG = "image/jpeg";
    public static final String GIF = "image/gif";

    private MimeType() {
      throw new IllegalStateException("Utility class");
    }
  }

  enum ImportMode {
    REGISTER,
    UPDATE
  }

  String EXIST_MESSAGE = "EXIST";
  String SUCCESS_MESSAGE = "SUCCESS";
  String HANDLE_ERR_MESSAGE = "Lỗi xử lý";
  String NOT_FOUND_MESSAGE = "Không tìm thấy bản ghi";
  String REQUIRED_UUID_MESSAGE = "Uuid is required";
  String NO_DATA_EXPORT_MESSAGE = "Không có dữ liệu xuất báo cáo";
  String NOT_DELETE = "Danh mục đang sử dụng không được phép xóa";
  String NOT_DELETE_2 = "Dữ liệu đang sử dụng không được phép xóa";
  String ERROR_EXPORT = "Lỗi báo cáo!";
  String NOT_FOUND_DATA = "Không có dữ liệu!";

  String EXIST = "Người dùng đã đăng ký";
  String EXIST_DATA_MESSAGE = "Dữ liệu đã tồn tại";
  String EXIST_CATEGORY_MESSAGE = "Danh mục đang sử dụng, không thể cập nhật";
  String NOT_EXIST_EFFECTIVE_DATE_MESSAGE = "Ngày ban hành không tồn tại";
  String PROMULGATED = "Quy trình đã được ban hành";
  String CAN_NOT_CHANGE_STATUS = "Không thể chuyển đổi trạng thái";
  String MISSING_REQUIRE_INFO = "Thiếu thông tin bắt buộc!";
}
