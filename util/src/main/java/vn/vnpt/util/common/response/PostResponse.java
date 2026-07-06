package vn.vnpt.util.common.response;

import lombok.Builder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import vn.vnpt.util.common.constant.SendGridConstant;

public class PostResponse<G> extends ResponseEntity<BaseResponseBody<G>> {

    @Builder
    public PostResponse(String status, HttpStatus code, String message, G body, String uuid, Integer id){
        super(new BaseResponseBody<G>(status,code.value(),message,body, uuid, id), code);
    }

    public static <G> PostResponseBuilder<G> success(){
        return PostResponse.<G> builder().code(HttpStatus.OK).status(SendGridConstant.RequestSendStatus.CODE_200.getMessage());
    }

    public static <G> PostResponseBuilder<G> error(){
        return PostResponse.<G> builder().code(HttpStatus.OK).status(SendGridConstant.RequestSendStatus.CODE_500.getMessage());
    }
}