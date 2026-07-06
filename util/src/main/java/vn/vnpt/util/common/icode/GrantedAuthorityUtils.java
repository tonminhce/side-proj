package vn.vnpt.util.common.icode;

import java.util.*;
import java.util.stream.Collectors;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

public class GrantedAuthorityUtils {
  public static GrantedAuthority addRolePrefix(String role) {
    return Objects.isNull(role)
        ? null
        : new SimpleGrantedAuthority(Objects.requireNonNull(AuthorityUtils.addRolePrefix(role)));
  }

  public static Set<GrantedAuthority> addRolePrefix(Collection<String> roles) {
    return Objects.isNull(roles)
        ? Collections.emptySet()
        : roles.stream().map(GrantedAuthorityUtils::addRolePrefix).collect(Collectors.toSet());
  }

  public static GrantedAuthority addGroupPrefix(String group) {
    return Objects.isNull(group)
        ? null
        : new SimpleGrantedAuthority(Objects.requireNonNull(AuthorityUtils.addGroupPrefix(group)));
  }

  public static Set<GrantedAuthority> addGroupPrefix(Collection<String> groups) {
    return Objects.isNull(groups)
        ? Collections.emptySet()
        : groups.stream().map(GrantedAuthorityUtils::addGroupPrefix).collect(Collectors.toSet());
  }

  public static GrantedAuthority addPermissionPrefix(String permission) {
    return Objects.isNull(permission)
        ? null
        : new SimpleGrantedAuthority(
            Objects.requireNonNull(AuthorityUtils.addPermissionPrefix(permission)));
  }

  public static Set<GrantedAuthority> addPermissionPrefix(Collection<String> permissions) {
    return Objects.isNull(permissions)
        ? Collections.emptySet()
        : permissions.stream()
            .map(GrantedAuthorityUtils::addPermissionPrefix)
            .collect(Collectors.toSet());
  }

  public static Set<GrantedAuthority> create(Collection<String> authorities) {
    return Objects.isNull(authorities)
        ? null
        : authorities.stream().map(SimpleGrantedAuthority::new).collect(Collectors.toSet());
  }

  public static Set<GrantedAuthority> optimize(Collection<GrantedAuthority> authorities) {
    if (Objects.isNull(authorities)) {
      return null;
    } else {
      Set<GrantedAuthority> optimizedAuthorities = new HashSet<>();

      for (GrantedAuthority a1 : authorities) {
        boolean isCandidate = true;

        for (GrantedAuthority a2 : authorities) {
          int relationship = AuthorityUtils.checkRelationship(a1.getAuthority(), a2.getAuthority());
          if (relationship == 2) {
            isCandidate = false;
            break;
          }
        }

        if (isCandidate) {
          optimizedAuthorities.add(a1);
        }
      }

      return optimizedAuthorities;
    }
  }
}
