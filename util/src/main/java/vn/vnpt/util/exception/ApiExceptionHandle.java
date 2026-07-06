package vn.vnpt.util.exception;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import jakarta.validation.ConstraintViolationException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.validation.BindException;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

/** Bộ xử lý ngoại lệ tập trung cho toàn bộ API, chuẩn hóa mã lỗi và thông điệp phản hồi. */
@RestControllerAdvice
@Slf4j
public class ApiExceptionHandle {

  @ExceptionHandler(CustomException.class)
  @ResponseStatus(value = HttpStatus.CONFLICT)
  public ExceptionMessage ahandleException(Exception ex, WebRequest request) {
    // Quá trình kiểm soát lỗi
    return new ExceptionMessage(HttpStatus.CONFLICT.value(), ex.getLocalizedMessage());
  }

  // @ExceptionHandler(GreenException.class)
  // @ResponseStatus(HttpStatus.BAD_REQUEST)
  // public ExceptionMessage customGreenException(GreenException ex) {
  //     return new ExceptionMessage(ex.getErrorCode().getValue(), ex.getMessage(), ex.getData());
  // }

  @ExceptionHandler(GreenException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ExceptionMessage handleGreenException(GreenException ex) {
    return new ExceptionMessage(ex.getErrorCode().getValue(), ex.getMessage(), ex.getData());
  }

  // Update Exception
  @ExceptionHandler(UpdateException.class)
  @ResponseStatus(value = HttpStatus.BAD_REQUEST)
  public ExceptionMessage updateException(Exception ex, WebRequest request) {
    // Quá trình kiểm soát lỗi
    return new ExceptionMessage(HttpStatus.BAD_REQUEST.value(), ex.getLocalizedMessage());
  }

  /*
   * IndexOutOfBoundsException sẽ được xử lý riêng tại đây
   */
  // Xử lý check hợp lệ dữ liệu
  @ExceptionHandler(MethodArgumentNotValidException.class)
  @ResponseStatus(value = HttpStatus.BAD_REQUEST)
  public ExceptionMessage checkValidException(MethodArgumentNotValidException ex) {
    Map<String, String> invalidInputs = new HashMap<>();

    ex.getBindingResult()
        .getFieldErrors()
        .forEach(err -> invalidInputs.put(err.getField(), err.getDefaultMessage()));

    String message = invalidInputs.values().stream().collect(Collectors.joining(". "));
    return new ExceptionMessage(
        HttpStatus.BAD_REQUEST.value(), StringUtils.capitalize(message), invalidInputs);
  }

  @ExceptionHandler(ConstraintViolationException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ExceptionMessage handleConstraintViolation(ConstraintViolationException ex) {

    Map<String, String> invalidInputs = new HashMap<>();

    ex.getConstraintViolations()
        .forEach(
            violation -> {
              String field = violation.getPropertyPath().toString();
              invalidInputs.put(field, violation.getMessage());
            });

    String message = invalidInputs.values().stream().collect(Collectors.joining(". "));
    return new ExceptionMessage(
        HttpStatus.BAD_REQUEST.value(), StringUtils.capitalize(message), invalidInputs);
  }

  @ExceptionHandler(InvalidInputException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ExceptionMessage handleInvalidInputException(InvalidInputException ex) {
    return new ExceptionMessage(
        HttpStatus.BAD_REQUEST.value(),
        ex.getErrors().values().stream().collect(Collectors.joining(". ")),
        ex.getErrors());
  }

  /*
  @ExceptionHandler(ResponseStatusException.class)
  public ExceptionMessage handleResponseStatusException(ResponseStatusException ex) {
      return new ExceptionMessage(ErrorCodeEnum.CONSTRAINT.getValue(), ex.getReason());
  }
  */

  // Xử lý lỗi dữ liệu không hợp lệ dữ liệu
  @ExceptionHandler(ResponseStatusException.class)
  @ResponseStatus(value = HttpStatus.BAD_REQUEST)
  public ExceptionMessage checkValidDataException(ResponseStatusException ex) {
    return new ExceptionMessage(ex.getStatusCode().value(), StringUtils.capitalize(ex.getReason()));
  }

  @ExceptionHandler(HandlerMethodValidationException.class)
  @ResponseStatus(value = HttpStatus.BAD_REQUEST)
  public ExceptionMessage handleMethodValidation(HandlerMethodValidationException ex) {
    Map<String, String> invalidInputs = new HashMap<>();
    ex.getParameterValidationResults()
        .forEach(
            result -> {
              result
                  .getResolvableErrors()
                  .forEach(
                      error -> {
                        String field = "";
                        Object[] args = error.getArguments();
                        if (args != null && args.length > 0) {
                          if (args[0] instanceof DefaultMessageSourceResolvable argResolvable) {
                            field = argResolvable.getDefaultMessage();
                          } else {
                            field = args[0].toString();
                          }
                        }
                        invalidInputs.put(field, error.getDefaultMessage());
                      });
            });

    String message = invalidInputs.values().stream().collect(Collectors.joining(". "));
    return new ExceptionMessage(
        ex.getStatusCode().value(), StringUtils.capitalize(message), invalidInputs);
  }

  /*
   * Tất cả các Exception không được khai báo sẽ được xử lý tại đây
   */
  @ExceptionHandler(Exception.class)
  @ResponseStatus(value = HttpStatus.INTERNAL_SERVER_ERROR)
  public ExceptionMessage handleException(Exception ex, WebRequest request) {
    log.error(ex.getMessage());
    ex.printStackTrace();
    // Quá trình kiểm soát lỗi
    return new ExceptionMessage(1000, "Đã xảy ra lỗi hệ thống. Vui lòng thử lại sau!");
  }

  @ExceptionHandler({BindException.class})
  @ResponseStatus(value = HttpStatus.BAD_REQUEST)
  public ExceptionMessage checkValidParamException(BindException ex) {
    List<ObjectError> errors = ex.getBindingResult().getAllErrors();
    String message =
        errors.stream()
            .map(DefaultMessageSourceResolvable::getDefaultMessage)
            .collect(Collectors.joining(". "));
    return new ExceptionMessage(HttpStatus.BAD_REQUEST.value(), StringUtils.capitalize(message));
  }

  // Xử lý lỗi upload file quá dung lượng cho phép
  @ExceptionHandler({MaxUploadSizeExceededException.class})
  @ResponseStatus(value = HttpStatus.PAYLOAD_TOO_LARGE)
  public ExceptionMessage MaxUploadSizeExceededException(Exception ex, WebRequest request) {
    log.error(ex.getMessage());
    return new ExceptionMessage(
        HttpStatus.PAYLOAD_TOO_LARGE.value(), "File upload quá dung lượng 50MB cho phép.");
  }

  // xử lý valid entity
  @ExceptionHandler(TransactionSystemException.class)
  @ResponseStatus(value = HttpStatus.BAD_REQUEST)
  public ExceptionMessage handleTransactionSystemException(TransactionSystemException ex) {
    Throwable cause = ex.getRootCause();
    if (cause instanceof ConstraintViolationException cve) {
      String message =
          cve.getConstraintViolations().stream()
              .map(v -> v.getPropertyPath() + ": " + v.getMessage())
              .collect(Collectors.joining(", "));
      return new ExceptionMessage(HttpStatus.BAD_REQUEST.value(), StringUtils.capitalize(message));
    }
    return new ExceptionMessage(1000, "Lỗi transaction");
  }

  @ExceptionHandler(AccessDeniedException.class)
  @ResponseStatus(HttpStatus.FORBIDDEN)
  public ExceptionMessage handleAccessDeniedException(
      AccessDeniedException ex, WebRequest request) {
    return new ExceptionMessage(HttpStatus.FORBIDDEN.value(), ex.getLocalizedMessage());
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ExceptionMessage handleHttpMessageNotReadableException(
      HttpMessageNotReadableException ex) {
    String message = "Dữ liệu JSON không hợp lệ";
    if (ex.getCause() instanceof InvalidFormatException ife) {
      String fieldName = ife.getPath().get(0).getFieldName();
      String value = ife.getValue().toString();
      message = String.format("Giá trị '%s' không hợp lệ cho trường '%s'", value, fieldName);
    }
    return new ExceptionMessage(HttpStatus.BAD_REQUEST.value(), message);
  }

  /**
   * Xử lý lỗi vi phạm ràng buộc toàn vẹn dữ liệu SQL (unique constraint, foreign key, not null,
   * v.v.)
   *
   * @param ex ngoại lệ DataIntegrityViolationException
   * @return thông báo lỗi chuẩn hóa
   */
  @ExceptionHandler(DataIntegrityViolationException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  public ExceptionMessage handleDataIntegrityViolationException(
      DataIntegrityViolationException ex) {
    log.error("Lỗi vi phạm toàn vẹn dữ liệu: {}", ex.getMessage());
    Throwable cause = ex.getMostSpecificCause();
    String message =
        SqlExceptionMessageResolver.resolveSqlConstraintMessage(
            cause, SqlExceptionConstant.EXCEPTION_SQL_CONSTRAINT_GENERIC);
    return new ExceptionMessage(HttpStatus.CONFLICT.value(), message);
  }

  /**
   * Xử lý lỗi trùng khóa chính hoặc unique key khi thao tác với cơ sở dữ liệu
   *
   * @param ex ngoại lệ DuplicateKeyException
   * @return thông báo lỗi chuẩn hóa
   */
  @ExceptionHandler(DuplicateKeyException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  public ExceptionMessage handleDuplicateKeyException(DuplicateKeyException ex) {
    log.error("Lỗi trùng khóa dữ liệu: {}", ex.getMessage());
    Throwable cause = ex.getMostSpecificCause();
    String message =
        SqlExceptionMessageResolver.resolveSqlConstraintMessage(
            cause, SqlExceptionConstant.EXCEPTION_SQL_DUPLICATE_GENERIC);
    return new ExceptionMessage(HttpStatus.CONFLICT.value(), message);
  }

  /**
   * Xử lý lỗi cú pháp SQL không hợp lệ
   *
   * @param ex ngoại lệ BadSqlGrammarException
   * @return thông báo lỗi chuẩn hóa
   */
  @ExceptionHandler(BadSqlGrammarException.class)
  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  public ExceptionMessage handleBadSqlGrammarException(BadSqlGrammarException ex) {
    log.error("Lỗi cú pháp SQL: {}", ex.getMessage());
    return new ExceptionMessage(
        HttpStatus.INTERNAL_SERVER_ERROR.value(), SqlExceptionConstant.EXCEPTION_SQL_BAD_GRAMMAR);
  }

  /**
   * Xử lý lỗi deadlock trong cơ sở dữ liệu
   *
   * @param ex ngoại lệ DeadlockLoserDataAccessException
   * @return thông báo lỗi chuẩn hóa
   */
  @ExceptionHandler(DeadlockLoserDataAccessException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  public ExceptionMessage handleDeadlockException(DeadlockLoserDataAccessException ex) {
    log.error("Lỗi deadlock cơ sở dữ liệu: {}", ex.getMessage());
    return new ExceptionMessage(
        HttpStatus.CONFLICT.value(), SqlExceptionConstant.EXCEPTION_SQL_DEADLOCK);
  }

  /**
   * Xử lý lỗi không thể khóa bản ghi (lock timeout) trong cơ sở dữ liệu
   *
   * @param ex ngoại lệ CannotAcquireLockException
   * @return thông báo lỗi chuẩn hóa
   */
  @ExceptionHandler(CannotAcquireLockException.class)
  @ResponseStatus(HttpStatus.CONFLICT)
  public ExceptionMessage handleCannotAcquireLockException(CannotAcquireLockException ex) {
    log.error("Lỗi không thể khóa bản ghi: {}", ex.getMessage());
    return new ExceptionMessage(
        HttpStatus.CONFLICT.value(), SqlExceptionConstant.EXCEPTION_SQL_CANNOT_ACQUIRE_LOCK);
  }

  /**
   * Xử lý lỗi kết nối hoặc truy cập cơ sở dữ liệu chung (DataAccessException)
   *
   * @param ex ngoại lệ DataAccessException
   * @return thông báo lỗi chuẩn hóa
   */
  @ExceptionHandler(DataAccessException.class)
  @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
  public ExceptionMessage handleDataAccessException(DataAccessException ex) {
    log.error("Lỗi truy cập cơ sở dữ liệu: {}", ex.getMessage());
    return new ExceptionMessage(
        HttpStatus.SERVICE_UNAVAILABLE.value(),
        SqlExceptionConstant.EXCEPTION_SQL_DATA_ACCESS_UNAVAILABLE);
  }

  @ExceptionHandler({MethodArgumentTypeMismatchException.class, TypeMismatchException.class})
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ExceptionMessage handleTypeMismatchException(Exception ex) {
    String message = "Tham số truy vấn sai định dạng: " + ex.getMessage();
    if (ex instanceof MethodArgumentTypeMismatchException mismatchEx) {
      message =
          String.format(
              "Tham số '%s' có giá trị '%s' không đúng định dạng yêu cầu",
              mismatchEx.getName(), mismatchEx.getValue());
    }
    return new ExceptionMessage(HttpStatus.BAD_REQUEST.value(), message);
  }
}
