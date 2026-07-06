package vn.vnpt.util.exception;

import java.util.Map;
import lombok.*;
import vn.vnpt.util.annotation.SpecialSymbolConstraint;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ExceptionMessage {
  private int statusCode;
  @SpecialSymbolConstraint private String message;
  private Map<String, String> invalidInputs;

  private String uuid;
  private Object data;

  public ExceptionMessage(String message) {
    this.message = message;
  }

  public ExceptionMessage(int statusCode, String message) {
    this.message = message;
    this.statusCode = statusCode;
    this.invalidInputs = null;
  }

  public ExceptionMessage(int statusCode, String message, Map<String, String> invalidInputs) {
    this.message = message;
    this.statusCode = statusCode;
    this.invalidInputs = invalidInputs;
  }

  public ExceptionMessage(int statusCode, String message, Object data) {
    this.message = message;
    this.statusCode = statusCode;
    this.data = data;
  }
}
