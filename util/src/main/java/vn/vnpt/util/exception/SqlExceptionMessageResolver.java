package vn.vnpt.util.exception;

import java.sql.SQLException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

/**
 * Tiện ích chuẩn hóa thông điệp lỗi SQL, ưu tiên ánh xạ theo SQLState PostgreSQL và bổ sung
 * metadata bảng/trường nếu có trong thông điệp gốc.
 */
@Slf4j
public final class SqlExceptionMessageResolver {

  /** Khởi tạo private để ngăn khởi tạo utility class. */
  private SqlExceptionMessageResolver() {}

  /**
   * Chuẩn hóa thông điệp lỗi SQL theo chiến lược ưu tiên: 1) Ưu tiên ánh xạ SQLState PostgreSQL. 2)
   * Fallback phân tích keyword trong thông điệp gốc. 3) Không xác định được thì trả về thông điệp
   * mặc định.
   *
   * @param throwable nguyên nhân gốc của ngoại lệ
   * @param defaultMessage thông điệp mặc định
   * @return thông điệp lỗi đã được chuẩn hóa
   */
  public static String resolveSqlConstraintMessage(Throwable throwable, String defaultMessage) {
    String sqlStateMessage = resolvePostgresSqlStateMessage(throwable);
    if (sqlStateMessage != null) {
      return sqlStateMessage;
    }

    return resolveByKeyword(throwable, defaultMessage);
  }

  /**
   * Ánh xạ SQLState PostgreSQL sang thông điệp nghiệp vụ ổn định.
   *
   * @param throwable nguyên nhân gốc của ngoại lệ
   * @return thông điệp đã chuẩn hóa theo SQLState hoặc null nếu không xác định được
   */
  public static String resolvePostgresSqlStateMessage(Throwable throwable) {
    SQLException sqlException = findSqlException(throwable);
    if (sqlException == null || StringUtils.isBlank(sqlException.getSQLState())) {
      return null;
    }

    String sqlState = sqlException.getSQLState();
    log.debug("Phát hiện SQLState PostgreSQL: {}", sqlState);

    return switch (sqlState) {
      case "23505" ->
          buildSqlConstraintMessage(
              throwable, SqlExceptionConstant.EXCEPTION_SQL_UNIQUE_CONSTRAINT);
      case "23503" ->
          buildSqlConstraintMessage(
              throwable, SqlExceptionConstant.EXCEPTION_SQL_FOREIGN_KEY_CONSTRAINT);
      case "23502" ->
          buildSqlConstraintMessage(
              throwable, SqlExceptionConstant.EXCEPTION_SQL_NOT_NULL_CONSTRAINT);
      case "23514" ->
          buildSqlConstraintMessage(throwable, SqlExceptionConstant.EXCEPTION_SQL_CHECK_CONSTRAINT);
      case "22001" ->
          buildSqlConstraintMessage(throwable, SqlExceptionConstant.EXCEPTION_SQL_LENGTH_EXCEEDED);
      case "22P02" ->
          buildSqlConstraintMessage(
              throwable, SqlExceptionConstant.EXCEPTION_SQL_INVALID_TYPE_FORMAT);
      default -> null;
    };
  }

  /**
   * Fallback phân loại lỗi SQL bằng keyword trong thông điệp gốc.
   *
   * @param throwable nguyên nhân gốc của ngoại lệ
   * @param defaultMessage thông điệp mặc định
   * @return thông điệp đã chuẩn hóa theo keyword hoặc thông điệp mặc định
   */
  private static String resolveByKeyword(Throwable throwable, String defaultMessage) {
    if (throwable == null || throwable.getMessage() == null) {
      return defaultMessage;
    }

    String causeMsg = throwable.getMessage().toLowerCase();
    if (causeMsg.contains("duplicate") || causeMsg.contains("unique")) {
      return buildSqlConstraintMessage(
          throwable, SqlExceptionConstant.EXCEPTION_SQL_UNIQUE_CONSTRAINT);
    }
    if (causeMsg.contains("foreign key") || causeMsg.contains("violates foreign key")) {
      return buildSqlConstraintMessage(
          throwable, SqlExceptionConstant.EXCEPTION_SQL_FOREIGN_KEY_CONSTRAINT);
    }
    if (causeMsg.contains("not-null") || causeMsg.contains("null value in column")) {
      return buildSqlConstraintMessage(
          throwable, SqlExceptionConstant.EXCEPTION_SQL_NOT_NULL_CONSTRAINT);
    }
    if (causeMsg.contains("check constraint") || causeMsg.contains("violates check")) {
      return buildSqlConstraintMessage(
          throwable, SqlExceptionConstant.EXCEPTION_SQL_CHECK_CONSTRAINT);
    }

    return defaultMessage;
  }

  /**
   * Chuẩn hóa thông báo lỗi SQL và bổ sung tên bảng/trường nếu trích xuất được từ thông điệp gốc.
   *
   * @param throwable nguyên nhân gốc của ngoại lệ
   * @param fallbackMessage thông báo mặc định khi không trích xuất được metadata
   * @return thông báo lỗi đã chuẩn hóa
   */
  public static String buildSqlConstraintMessage(Throwable throwable, String fallbackMessage) {
    if (throwable == null || throwable.getMessage() == null) {
      return fallbackMessage;
    }

    String exceptionMessage = throwable.getMessage();

    String tableName = extractFirstGroup(exceptionMessage, "(?i)relation\\s+\"([^\"]+)\"");
    if (tableName == null) {
      tableName = extractFirstGroup(exceptionMessage, "(?i)table\\s+\"([^\"]+)\"");
    }
    if (tableName == null) {
      tableName = extractFirstGroup(exceptionMessage, "(?i)for key '\\w+\\.([^']+)'");
    }
    if (tableName == null) {
      tableName = extractFirstGroup(exceptionMessage, "(?i)`[^`]+`\\.`([^`]+)`");
    }

    String fieldName = extractFirstGroup(exceptionMessage, "(?i)column\\s+\"([^\"]+)\"");
    if (fieldName == null) {
      fieldName = extractFirstGroup(exceptionMessage, "(?i)key\\s*\\(([^)]+)\\)");
    }
    if (fieldName == null) {
      fieldName = extractFirstGroup(exceptionMessage, "(?i)foreign key\\s*\\(`([^`]+)`\\)");
    }

    SQLException sqlException = findSqlException(throwable);
    if (sqlException != null) {
      log.debug(
          "SQLState: {}, ErrorCode: {}", sqlException.getSQLState(), sqlException.getErrorCode());
    }

    if (tableName != null && fieldName != null) {
      return String.format(
          SqlExceptionConstant.EXCEPTION_SQL_METADATA_TABLE_FIELD_TEMPLATE,
          fallbackMessage,
          tableName,
          fieldName);
    }
    if (tableName != null) {
      return String.format(
          SqlExceptionConstant.EXCEPTION_SQL_METADATA_TABLE_TEMPLATE, fallbackMessage, tableName);
    }
    if (fieldName != null) {
      return String.format(
          SqlExceptionConstant.EXCEPTION_SQL_METADATA_FIELD_TEMPLATE, fallbackMessage, fieldName);
    }

    return fallbackMessage;
  }

  /**
   * Tìm SQLException trong chuỗi nguyên nhân để hỗ trợ debug SQLState/ErrorCode.
   *
   * @param throwable nguyên nhân gốc
   * @return SQLException nếu có, ngược lại trả về null
   */
  private static SQLException findSqlException(Throwable throwable) {
    Throwable current = throwable;
    while (current != null) {
      if (current instanceof SQLException sqlException) {
        return sqlException;
      }
      current = current.getCause();
    }
    return null;
  }

  /**
   * Trích xuất nhóm đầu tiên từ chuỗi đầu vào theo biểu thức chính quy.
   *
   * @param input chuỗi cần phân tích
   * @param regex biểu thức chính quy
   * @return giá trị nhóm thứ nhất nếu khớp, ngược lại trả về null
   */
  private static String extractFirstGroup(String input, String regex) {
    if (StringUtils.isBlank(input)) {
      return null;
    }

    Matcher matcher = Pattern.compile(regex).matcher(input);
    if (matcher.find()) {
      return matcher.group(1);
    }

    return null;
  }
}
