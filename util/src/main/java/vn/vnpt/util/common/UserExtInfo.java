package vn.vnpt.util.common;

import lombok.*;

import java.io.Serial;
import java.io.Serializable;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class UserExtInfo implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    private Integer id;
    private String uuid;
    private String positionId;
    private Long organizationUuid;
    private Organization organization;

    public boolean canHandle(Organization organization) {
        return this.organization == null ? false : this.organization.isParentOf(organization);
    }
}
