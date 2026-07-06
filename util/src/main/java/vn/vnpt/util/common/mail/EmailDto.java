package vn.vnpt.util.common.mail;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class EmailDto {
    private String to;
    private String fullName;
    private String username;
    private String password;
    private String rejectionReason;
    private String updateLink;
}
