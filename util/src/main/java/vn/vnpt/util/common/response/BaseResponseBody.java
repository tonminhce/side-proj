package vn.vnpt.util.common.response;

import lombok.*;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class BaseResponseBody<G> {
    private String status;
    private int code;
    private String message;
    private G data;
    private String uuid;
    private Integer id;
}
