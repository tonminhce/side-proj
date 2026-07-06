package vn.vnpt.util.telegram;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ErrorMessageObj {
    private String requestBody;
    private ContentCachingRequestWrapper wrappedReq;
    private ContentCachingResponseWrapper wrappedRes;
    private String requestURI;
    private String method;
    private int status;
    private String service;
    private Exception exceptionCaught;
    private String remoteHost;
    private String username;
}
