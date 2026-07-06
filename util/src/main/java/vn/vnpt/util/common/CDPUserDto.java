package vn.vnpt.util.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CDPUserDto {
    String userName;
    String password;
    String scope = "openid";
    String clientId = "green-dev";
    String grantType = "password";
}
