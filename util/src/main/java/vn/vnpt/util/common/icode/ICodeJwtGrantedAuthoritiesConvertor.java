package vn.vnpt.util.common.icode;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class ICodeJwtGrantedAuthoritiesConvertor implements Converter<Jwt, Collection<GrantedAuthority>> {
    private final Log logger = LogFactory.getLog(ICodeJwtGrantedAuthoritiesConvertor.class);
    private String groupsClaim = "groups";
    private String rolesClaim = "app_roles";
    private String permissionsClaim = "app_permissions";

    public ICodeJwtGrantedAuthoritiesConvertor() {
    }

    public ICodeJwtGrantedAuthoritiesConvertor(String groupsClaim, String rolesClaim, String permissionsClaim) {
        this.groupsClaim = groupsClaim;
        this.rolesClaim = rolesClaim;
        this.permissionsClaim = permissionsClaim;
    }

    public Collection<GrantedAuthority> convert(Jwt jwt) {
        List<GrantedAuthority> authorities = new ArrayList();
        List<String> roles = jwt.getClaimAsStringList(this.rolesClaim);
        List<String> groups = jwt.getClaimAsStringList(this.groupsClaim);
        List<String> permissions = jwt.getClaimAsStringList(this.permissionsClaim);
        this.logger.debug("roles: " + roles);
        this.logger.debug("groups: " + groups);
        this.logger.debug("permissions: " + permissions);
        authorities.addAll(GrantedAuthorityUtils.addRolePrefix(roles));
        authorities.addAll(GrantedAuthorityUtils.addGroupPrefix(groups));
        authorities.addAll(GrantedAuthorityUtils.addPermissionPrefix(permissions));
        this.logger.debug("authorities: " + authorities);
        return authorities;
    }

    public String getGroupsClaim() {
        return this.groupsClaim;
    }

    public void setGroupsClaim(String groupsClaim) {
        this.groupsClaim = groupsClaim;
    }

    public String getRolesClaim() {
        return this.rolesClaim;
    }

    public void setRolesClaim(String rolesClaim) {
        this.rolesClaim = rolesClaim;
    }

    public String getPermissionsClaim() {
        return this.permissionsClaim;
    }

    public void setPermissionsClaim(String permissionsClaim) {
        this.permissionsClaim = permissionsClaim;
    }
}
