package vn.vnpt.util.common;

import java.util.Map;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class RestAPIUtils {
  private final RestTemplate restTemplate;

  public RestAPIUtils(RestTemplate restTemplate) {
    this.restTemplate = restTemplate;
  }

  public <T, V> V callApi(
      T body,
      String url,
      HttpHeaders httpHeaders,
      HttpMethod method,
      Class<V> massage,
      Map<String, String> params) {
    HttpEntity<T> request = new HttpEntity<>(body, httpHeaders);
    V response = restTemplate.exchange(url, method, request, massage, params).getBody();
    return response;
  }
}
