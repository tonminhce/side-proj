package vn.vnpt.util.common.icode;

import lombok.Getter;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;

@Getter
public class ICodeUser implements UserDetails {
    private final String deploymentId;
    private String userStoreDomain;
    private final String username;
    private final String password;
    private final Boolean enabled;
    private final Collection<GrantedAuthority> authorities;

    public ICodeUser(Username objUsername, String password, Boolean enabled, Collection<GrantedAuthority> authorities) {
        this.deploymentId = objUsername.getDeploymentId();
        this.userStoreDomain = objUsername.getUserStoreDomain();
        this.username = objUsername.getUsername();
        this.password = password;
        this.enabled = enabled;
        this.authorities = authorities;
    }

    public ICodeUser(String deploymentId, String username, String password, Boolean enabled, Collection<GrantedAuthority> authorities) {
        this.deploymentId = deploymentId;
        this.username = username;
        this.password = password;
        this.enabled = enabled;
        this.authorities = authorities;
    }

    @NotNull
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return this.authorities;
    }

    public boolean isAccountNonExpired() {
        return true;
    }

    public boolean isAccountNonLocked() {
        return true;
    }

    public boolean isCredentialsNonExpired() {
        return true;
    }

    public boolean isEnabled() {
        return this.enabled;
    }

}
