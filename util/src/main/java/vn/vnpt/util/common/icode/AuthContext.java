package vn.vnpt.util.common.icode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

public class AuthContext {
  private final Log logger = LogFactory.getLog(AuthContext.class);

  public List<String> getPermissions() {
    List<String> permissions = new ArrayList<>();

    for (GrantedAuthority grantedAuthority : this.getAuthorities()) {
      String authority = grantedAuthority.getAuthority();
      if (authority.startsWith("PERMISSION_")) {
        permissions.add(AuthorityUtils.extractPermission(authority));
      }
    }

    this.logger.debug("getPermissions return: " + permissions);
    return permissions;
  }

  public List<String> getRoles() {
    List<String> roles = new ArrayList<>();

    for (GrantedAuthority grantedAuthority : this.getAuthorities()) {
      String authority = grantedAuthority.getAuthority();
      if (authority.startsWith("ROLE_")) {
        roles.add(AuthorityUtils.extractRole(authority));
      }
    }

    this.logger.debug("getRoles return: " + roles);
    return roles;
  }

  public List<String> getGroups() {
    List<String> groups = new ArrayList<>();

    for (GrantedAuthority grantedAuthority : this.getAuthorities()) {
      String authority = grantedAuthority.getAuthority();
      if (authority.startsWith("GROUP_")) {
        groups.add(AuthorityUtils.extractGroup(authority));
      }
    }

    this.logger.debug("getGroups return: " + groups);
    return groups;
  }

  public Authentication getAuthentication() {
    return SecurityContextHolder.getContext().getAuthentication();
  }

  public Collection<? extends GrantedAuthority> getAuthorities() {
    return this.getAuthentication().getAuthorities();
  }

  public boolean hasGroup(String group) {
    if (Objects.isNull(group)) {
      return false;
    } else {
      return this.getGroups().contains(group);
    }
  }

  public boolean hasGroup(String... groups) {
    if (Objects.isNull(groups)) {
      return false;
    } else {
      for (String group : groups) {
        if (!this.hasGroup(group)) {
          return false;
        }
      }

      return true;
    }
  }

  public boolean hasAnyGroup(String... groups) {
    for (int i = 0; i < groups.length; ++i) {
      if (this.hasGroup(groups[i])) {
        return true;
      }
    }

    return false;
  }

  public boolean hasRole(String role) {
    if (Objects.isNull(role)) {
      return false;
    } else {
      return this.getRoles().contains(role);
    }
  }

  public boolean hasRole(String... roles) {
    if (Objects.isNull(roles)) {
      return false;
    } else {
      for (String role : roles) {
        if (!this.hasRole(role)) {
          return false;
        }
      }

      return true;
    }
  }

  public boolean hasAnyRole(String... roles) {
    for (int i = 0; i < roles.length; ++i) {
      if (this.hasRole(roles[i])) {
        return true;
      }
    }

    return false;
  }

  public boolean hasPermission(String permission) {
    if (Objects.isNull(permission)) {
      return false;
    } else {
      return this.isSuperAdmin() || AuthorityUtils.hasAuthority(permission, this.getPermissions());
    }
  }

  public boolean hasPermission(String... permissions) {
    if (Objects.isNull(permissions)) {
      return false;
    } else {
      return this.isSuperAdmin() || AuthorityUtils.hasAuthority(permissions, this.getPermissions());
    }
  }

  public boolean hasAnyPermission(String... permissions) {
    for (int i = 0; i < permissions.length; ++i) {
      if (this.hasPermission(permissions[i])) {
        return true;
      }
    }

    return false;
  }

  public boolean isJwtAuthentication() {
    return SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken;
  }

  public String getTokenValue() {
    if (this.isJwtAuthentication()) {
      JwtAuthenticationToken jwtAuthenticationToken =
          (JwtAuthenticationToken) this.getAuthentication();
      return ((Jwt) jwtAuthenticationToken.getToken()).getTokenValue();
    } else {
      return null;
    }
  }

  public boolean isSuperAdmin() {
    return this.hasRole("SUPER_ADMIN");
  }

  public boolean isAdmin() {
    return this.hasRole("ADMIN");
  }

  public ICodeUser getICodeUser() {
    return (ICodeUser) this.getAuthentication().getPrincipal();
  }

  public String getDeploymentId() {
    if (this.isJwtAuthentication()) {
      JwtAuthenticationToken jwtAuthenticationToken =
          (JwtAuthenticationToken) this.getAuthentication();
      return ((Jwt) jwtAuthenticationToken.getToken()).getClaimAsString("deployment_id");
    } else {
      return this.getICodeUser().getDeploymentId();
    }
  }
}
