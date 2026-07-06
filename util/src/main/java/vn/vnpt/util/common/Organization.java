package vn.vnpt.util.common;

import lombok.*;

import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class Organization implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
    protected Long uuid;
    private String code;
    private String name;
    private Long parentUuid;
    private String orgPath;
    private Integer level;
    private Long provinceUuid;
    private Long communeUuid;
    private String addressDetail;
    private String description;

    public boolean isParentOf(Organization organization) {
        return Arrays.stream(organization.getOrgPath().split("/")).anyMatch(s -> s.equals(this.uuid + ""));
    }

    public boolean isChildOf(Long parentUuid) {
        return Arrays.stream(orgPath.split("/")).anyMatch(s -> s.equals(parentUuid + ""));
    }
}
