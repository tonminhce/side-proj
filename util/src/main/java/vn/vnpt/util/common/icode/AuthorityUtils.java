package vn.vnpt.util.common.icode;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public class AuthorityUtils {
    private static final Log logger = LogFactory.getLog(AuthorityUtils.class);

    public static Set<String> optimize(Collection<String> authorities) {
        if (Objects.isNull(authorities)) {
            return null;
        } else {
            Set<String> optimizedAuthorities = new HashSet<>();

            for(String a1 : authorities) {
                boolean isCandidate = true;

                for(String a2 : authorities) {
                    int relationship = checkRelationship(a1, a2);
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

    public static boolean hasAuthority(String authority, Collection<String> source) {
        if (!Objects.isNull(authority) && !Objects.isNull(source)) {
            for(String a : source) {
                int relationship = checkRelationship(authority, a);
                if (relationship == 0 || relationship == 2) {
                    return true;
                }
            }

            return false;
        } else {
            return false;
        }
    }

    public static boolean hasAuthority(String[] authorities, Collection<String> source) {
        if (!Objects.isNull(authorities) && !Objects.isNull(source)) {
            for(String authority : authorities) {
                if (!hasAuthority(authority, source)) {
                    return false;
                }
            }

            return true;
        } else {
            return false;
        }
    }

    public static int checkRelationship(String a1, String a2) {
        if (!Objects.isNull(a1) && !Objects.isNull(a2)) {
            if (Objects.equals(a1, a2)) {
                return 0;
            } else if (Objects.equals(a1, "/")) {
                return 1;
            } else if (Objects.equals(a2, "/")) {
                return 2;
            } else {
                String[] a1arr = a1.split("/");
                String[] a2arr = a2.split("/");
                if (a1arr.length < a2arr.length) {
                    for(int i = 0; i < a1arr.length; ++i) {
                        if (!Objects.equals(a1arr[i], a2arr[i])) {
                            return -1;
                        }
                    }

                    return 1;
                } else if (a1arr.length > a2arr.length) {
                    for(int i = 0; i < a2arr.length; ++i) {
                        if (!Objects.equals(a2arr[i], a1arr[i])) {
                            return -1;
                        }
                    }

                    return 2;
                } else {
                    for(int i = 0; i < a2arr.length; ++i) {
                        if (!Objects.equals(a2arr[i], a1arr[i])) {
                            return -1;
                        }
                    }

                    return 0;
                }
            }
        } else {
            return -1;
        }
    }

    public static String addRolePrefix(String role) {
        if (Objects.isNull(role)) {
            return null;
        } else {
            return role.startsWith("ROLE_") ? role : "ROLE_" + role;
        }
    }

    public static Set<String> addRolePrefix(Collection<String> roles) {
        return (Set)roles.stream().map(AuthorityUtils::addRolePrefix).collect(Collectors.toSet());
    }

    public static String addGroupPrefix(String group) {
        if (Objects.isNull(group)) {
            return null;
        } else {
            return group.startsWith("GROUP_") ? group : "GROUP_" + group;
        }
    }

    public static Set<String> addGroupPrefix(Collection<String> groups) {
        return (Set)groups.stream().map(AuthorityUtils::addGroupPrefix).collect(Collectors.toSet());
    }

    public static String addPermissionPrefix(String permission) {
        if (Objects.isNull(permission)) {
            return null;
        } else {
            return permission.startsWith("PERMISSION_") ? permission : "PERMISSION_" + permission;
        }
    }

    public static Set<String> addPermissionPrefix(Collection<String> permission) {
        return (Set)permission.stream().map(AuthorityUtils::addPermissionPrefix).collect(Collectors.toSet());
    }

    public static String extractPermission(String authority) {
        if (Objects.isNull(authority)) {
            return null;
        } else {
            return authority.startsWith("PERMISSION_") ? authority.substring("PERMISSION_".length()) : authority;
        }
    }

    public static String extractGroup(String authority) {
        if (Objects.isNull(authority)) {
            return null;
        } else {
            return authority.startsWith("GROUP_") ? authority.substring("GROUP_".length()) : authority;
        }
    }

    public static String extractRole(String authority) {
        if (Objects.isNull(authority)) {
            return null;
        } else {
            return authority.startsWith("ROLE_") ? authority.substring("ROLE_".length()) : authority;
        }
    }
}
