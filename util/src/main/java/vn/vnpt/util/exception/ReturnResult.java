package vn.vnpt.util.exception;

import lombok.AllArgsConstructor;
import lombok.Data;
import vn.vnpt.util.common.constant.ErrorCodeEnum;

@Data
@AllArgsConstructor
public class ReturnResult {
  private ErrorCodeEnum statusCode;
  private String message;
}
