package vn.vnpt.util.web;

import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Common HTTP error mapping. Services extend with their domain-specific handlers. */
@RestControllerAdvice
@Slf4j
public class RestExceptionHandler {

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<Map<String, Object>> handleValidation(IllegalArgumentException e) {
    log.debug("400 validation: {}", e.getMessage());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(Map.of("code", 400, "status", "BAD_REQUEST", "message", e.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, Object>> handleBeanValidation(MethodArgumentNotValidException e) {
    String message = e.getBindingResult().getFieldErrors().stream()
        .map(err -> err.getField() + ": " + err.getDefaultMessage())
        .reduce((a, b) -> a + "; " + b).orElse("validation failed");
    log.debug("400 bean validation: {}", message);
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(Map.of("code", 400, "status", "BAD_REQUEST", "message", message));
  }
}