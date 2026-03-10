package com.navneet.executespec.auth.security;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.OrgMember;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Spring Security UserDetails wrapper around AppUser + optional OrgMember context.
 */
@Getter
public class AppUserDetails implements UserDetails {

    private final AppUser user;
    private final OrgMember orgMember;  // null for free users / super admin

    public AppUserDetails(AppUser user, OrgMember orgMember) {
        this.user = user;
        this.orgMember = orgMember;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // Global role
        List<GrantedAuthority> authorities = new java.util.ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + user.getGlobalRole()));
        // Org role (if any)
        if (orgMember != null) {
            authorities.add(new SimpleGrantedAuthority("ROLE_" + orgMember.getOrgRole()));
        }
        return authorities;
    }

    @Override
    public String getPassword() {
        return user.getPasswordHash();
    }

    @Override
    public String getUsername() {
        return user.getEmail();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return !"SUSPENDED".equals(user.getStatus());
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return "ACTIVE".equals(user.getStatus());
    }
}
