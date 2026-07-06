package vn.vnpt.util.common;

import java.util.Map;
import java.util.Objects;
import org.springframework.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import vn.vnpt.util.exception.ErrorCode;

@Service
public class CDPApiUtil {
  public CDPTokenResultDto getTokenByAccountAndPassword(String url, CDPUserDto user) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
    RestTemplate restTemplate = new RestTemplate();
    try {
      // build the request
      JwtAuthenticationToken jwtAuthenticationToken =
          (JwtAuthenticationToken) SecurityContextHolder.getContext().getAuthentication();
      headers.setBearerAuth(
          Objects.requireNonNull(jwtAuthenticationToken).getToken().getTokenValue());

      MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
      body.add("grant_type", user.getGrantType());
      body.add("client_id", user.getClientId());
      body.add("scope", user.getScope());
      body.add("username", user.getUserName());
      body.add("password", user.getPassword());

      HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

      ResponseEntity<Map> result = restTemplate.exchange(url, HttpMethod.POST, request, Map.class);
      if (result.getStatusCode() != HttpStatus.OK) {
        return new CDPTokenResultDto(
            ErrorCode.UNKNOWN_ERROR.getValue(), null, "Lấy thông tin token thất bại");
      }
      return new CDPTokenResultDto(
          ErrorCode.SUCCESS.getValue(),
          Objects.requireNonNull(result.getBody()).get("access_token").toString(),
          "Lấy thông tin token thành công");
    } catch (Exception e) {
      return new CDPTokenResultDto(
          ErrorCode.UNKNOWN_ERROR.getValue(), null, "Lấy thông tin token thất bại");
    }
  }

  public CDPTokenResultDto getTokenWithNotToken(String url, CDPUserDto user) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
    RestTemplate restTemplate = new RestTemplate();
    try {
      MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
      body.add("grant_type", user.getGrantType() != null ? user.getGrantType() : "password");
      body.add("client_id", user.getClientId());
      body.add("scope", user.getScope());
      body.add("username", user.getUserName());
      body.add("password", user.getPassword());

      HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

      ResponseEntity<Map> result = restTemplate.exchange(url, HttpMethod.POST, request, Map.class);
      if (result.getStatusCode() != HttpStatus.OK) {
        return new CDPTokenResultDto(
            ErrorCode.UNKNOWN_ERROR.getValue(), null, "Lấy thông tin token thất bại");
      }
      return new CDPTokenResultDto(
          ErrorCode.SUCCESS.getValue(),
          Objects.requireNonNull(result.getBody()).get("access_token").toString(),
          "Lấy thông tin token thành công");
    } catch (Exception e) {
      return new CDPTokenResultDto(
          ErrorCode.UNKNOWN_ERROR.getValue(), null, "Lấy thông tin token thất bại");
    }
  }
}
