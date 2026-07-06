package vn.vnpt.util.common.component;

import org.springframework.core.MethodParameter;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@ControllerAdvice
public class PageMapResponseAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        // Accept tất cả response để kiểm tra runtime
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {

        // Trường hợp 1: ResponseEntity mà không có generic type
        if (body instanceof ResponseEntity) {
            ResponseEntity<?> responseEntity = (ResponseEntity<?>) body;
            Object responseBody = responseEntity.getBody();

            // Kiểm tra body có phải Page<Map<String, Object>> không
            if (responseBody instanceof Page) {
                Page<?> page = (Page<?>) responseBody;
                if (isPageOfMapStringObject(page)) {
                    Object convertedPage = convertPageMapToLowercase(page);
                    return ResponseEntity.status(responseEntity.getStatusCode())
                            .headers(responseEntity.getHeaders())
                            .body(convertedPage);
                }
            }
            return body;
        }

        // Trường hợp 2: Page<Map<String, Object>> trực tiếp
        if (body instanceof Page) {
            Page<?> page = (Page<?>) body;
            if (isPageOfMapStringObject(page)) {
                return convertPageMapToLowercase(page);
            }
        }

        return body;
    }

    /**
     * Kiểm tra runtime: Page có chứa Map<String, Object> không
     */
    private boolean isPageOfMapStringObject(Page<?> page) {
        if (page.isEmpty()) {
            return false;
        }

        Object firstElement = page.getContent().get(0);

        // Kiểm tra element đầu tiên có phải Map<String, Object> không
        if (!(firstElement instanceof Map)) {
            return false;
        }

        // Có thể thêm check nếu muốn chắc chắn là Map<String, Object>
        Map<?, ?> map = (Map<?, ?>) firstElement;
        if (map.isEmpty()) {
            return true; // Map rỗng cũng chấp nhận
        }

        // Kiểm tra key có phải String không
        Object firstKey = map.keySet().iterator().next();
        return firstKey instanceof String;
    }

    /**
     * Convert tất cả keys trong Map về lowercase
     */
//    private Object convertPageMapToLowercase(Page<?> page) {
//        @SuppressWarnings("unchecked")
//        Page<Map<String, Object>> mapPage = (Page<Map<String, Object>>) page;
//
//        return mapPage.map(map ->
//                map.entrySet().stream()
//                        .collect(Collectors.toMap(
//                                entry -> entry.getKey().toLowerCase(),
//                                Map.Entry::getValue,
//                                (v1, v2) -> v1,
//                                LinkedHashMap::new
//                        ))
//        );
//    }

    private Object convertPageMapToLowercase(Page<?> page) {
        @SuppressWarnings("unchecked")
        Page<Map<String, Object>> mapPage = (Page<Map<String, Object>>) page;

        return mapPage.map(map -> {
            Map<String, Object> lowercaseMap = new LinkedHashMap<>();
            map.forEach((key, value) ->
                    lowercaseMap.put(key.toLowerCase(), value)
            );
            return lowercaseMap;
        });
    }
}
