package vn.vnpt.util.common;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class User {
    private String iss;
    private Long uuid;
    private String name;
    private String preferred_username;
    private String external_user_id;
    private String given_name;
    private String deployment_id;
    private String username;
    private List<String> app_roles;
}

