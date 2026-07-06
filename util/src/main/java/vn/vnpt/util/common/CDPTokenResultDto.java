package vn.vnpt.util.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CDPTokenResultDto {
    private Integer statusCode;
    private String token;
    private String message;
}
