package vn.vnpt.util.common.icode;

import lombok.Getter;
import lombok.Setter;

import java.util.Objects;

@Getter
@Setter
public class Username {
    private String userStoreDomain;
    private String username;
    private String deploymentId;

    public Username(String fullUsername) {
        if (Objects.nonNull(fullUsername)) {
            String str = fullUsername;
            int idx1 = fullUsername.indexOf("/");
            if (idx1 > -1) {
                this.userStoreDomain = fullUsername.substring(0, idx1);
                str = fullUsername.substring(idx1 + 1);
            }

            int idx2 = str.indexOf("@");
            if (idx2 > -1) {
                this.username = str.substring(0, idx2);
                this.deploymentId = str.substring(idx2 + 1);
            } else {
                this.username = str;
            }
        }

    }

    public Username(String userStoreDomain, String username, String deploymentId) {
        this.userStoreDomain = userStoreDomain;
        this.username = username;
        this.deploymentId = deploymentId;
    }

    public boolean isMissingDeploymentId() {
        return Objects.isNull(this.deploymentId) || this.deploymentId.isEmpty();
    }

    public boolean isMissingUsername() {
        return Objects.isNull(this.username) || this.username.isEmpty();
    }

    public boolean isMissingUserStoreDomain() {
        return Objects.isNull(this.userStoreDomain) || this.userStoreDomain.isEmpty();
    }

    public String toString() {
        return this.userStoreDomain + "/" + this.username + "@" + this.deploymentId;
    }
}
