package vn.vnpt.util.exception;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ResponseResult {
    private Integer statusCode;
    private String message;
}
