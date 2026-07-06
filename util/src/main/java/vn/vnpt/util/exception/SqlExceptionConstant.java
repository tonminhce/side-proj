package vn.vnpt.util.exception;

/** Danh sách hằng số thông điệp ngoại lệ liên quan đến SQL. */
public final class SqlExceptionConstant {

  public static final String EXCEPTION_SQL_CONSTRAINT_GENERIC =
      "Dữ liệu vi phạm ràng buộc toàn vẹn cơ sở dữ liệu";
  public static final String EXCEPTION_SQL_DUPLICATE_GENERIC =
      "Dữ liệu đã tồn tại trong hệ thống, vui lòng kiểm tra lại";
  public static final String EXCEPTION_SQL_UNIQUE_CONSTRAINT =
      "Dữ liệu đã tồn tại, vi phạm ràng buộc duy nhất (unique constraint)";
  public static final String EXCEPTION_SQL_FOREIGN_KEY_CONSTRAINT =
      "Dữ liệu vi phạm ràng buộc khóa ngoại (foreign key constraint)";
  public static final String EXCEPTION_SQL_NOT_NULL_CONSTRAINT =
      "Dữ liệu vi phạm ràng buộc NOT NULL, vui lòng kiểm tra lại các trường bắt buộc";
  public static final String EXCEPTION_SQL_CHECK_CONSTRAINT =
      "Dữ liệu vi phạm ràng buộc kiểm tra (check constraint)";
  public static final String EXCEPTION_SQL_LENGTH_EXCEEDED =
      "Dữ liệu vượt quá độ dài cho phép của trường";
  public static final String EXCEPTION_SQL_INVALID_TYPE_FORMAT =
      "Dữ liệu đầu vào sai định dạng kiểu dữ liệu";
  public static final String EXCEPTION_SQL_BAD_GRAMMAR =
      "Đã xảy ra lỗi truy vấn cơ sở dữ liệu. Vui lòng thử lại sau!";
  public static final String EXCEPTION_SQL_DEADLOCK =
      "Hệ thống đang xử lý quá nhiều yêu cầu đồng thời. Vui lòng thử lại sau!";
  public static final String EXCEPTION_SQL_CANNOT_ACQUIRE_LOCK =
      "Không thể thực hiện thao tác do xung đột khóa dữ liệu. Vui lòng thử lại sau!";
  public static final String EXCEPTION_SQL_DATA_ACCESS_UNAVAILABLE =
      "Không thể kết nối hoặc truy cập cơ sở dữ liệu. Vui lòng thử lại sau!";

  public static final String EXCEPTION_SQL_METADATA_TABLE_FIELD_TEMPLATE =
      "%s [bảng: %s, trường: %s]";
  public static final String EXCEPTION_SQL_METADATA_TABLE_TEMPLATE = "%s [bảng: %s]";
  public static final String EXCEPTION_SQL_METADATA_FIELD_TEMPLATE = "%s [trường: %s]";

  /** Khởi tạo private để ngăn khởi tạo utility class. */
  private SqlExceptionConstant() {}
}
