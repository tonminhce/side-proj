package vn.vnpt.util.exception;

import java.util.List;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import vn.vnpt.util.common.constant.ErrorCodeEnum;

@RestControllerAdvice
public class UtilGlobalExceptionHandler {
  private final Logger log = LoggerFactory.getLogger(UtilGlobalExceptionHandler.class);

  @ExceptionHandler(CustomException.class)
  public ResponseEntity<ReturnResult> ahandleException(Exception ex) {
    return new ResponseEntity<>(
        new ReturnResult(ErrorCodeEnum.CONFLICT, ex.getLocalizedMessage()), HttpStatus.CONFLICT);
  }

  @ExceptionHandler(UpdateException.class)
  public ResponseEntity<ReturnResult> updateException(Exception ex) {
    return new ResponseEntity<>(
        new ReturnResult(ErrorCodeEnum.BAD_REQUEST, ex.getLocalizedMessage()),
        HttpStatus.BAD_REQUEST);
  }

  @ExceptionHandler({BindException.class})
  public ResponseEntity<ReturnResult> checkValidException(MethodArgumentNotValidException ex) {
    List<ObjectError> errors = ex.getBindingResult().getAllErrors();
    String message =
        errors.stream()
            .map(DefaultMessageSourceResolvable::getDefaultMessage)
            .collect(Collectors.joining(". "));
    return new ResponseEntity<>(
        new ReturnResult(ErrorCodeEnum.BAD_REQUEST, StringUtils.capitalize(message)),
        HttpStatus.BAD_REQUEST);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ReturnResult> checkValidParamException(BindException ex) {
    List<ObjectError> errors = ex.getBindingResult().getAllErrors();
    String message =
        errors.stream()
            .map(DefaultMessageSourceResolvable::getDefaultMessage)
            .collect(Collectors.joining(". "));
    return new ResponseEntity<>(
        new ReturnResult(ErrorCodeEnum.BAD_REQUEST, StringUtils.capitalize(message)),
        HttpStatus.BAD_REQUEST);
  }

  @ExceptionHandler({MaxUploadSizeExceededException.class})
  public ResponseEntity<ReturnResult> MaxUploadSizeExceededException(Exception ex) {
    log.error("Unhandled exception: ", ex);
    return new ResponseEntity<>(
        new ReturnResult(ErrorCodeEnum.PAYLOAD_TOO_LARGE, "File upload quá dung lượng cho phép."),
        HttpStatus.PAYLOAD_TOO_LARGE);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ReturnResult> handleGeneralException(Exception ex) {
    log.error("Unhandled exception: ", ex);
    return new ResponseEntity<>(
        new ReturnResult(ErrorCodeEnum.INTERNAL_SERVER_ERROR, "Internal Server Error"),
        HttpStatus.INTERNAL_SERVER_ERROR);
  }
}
