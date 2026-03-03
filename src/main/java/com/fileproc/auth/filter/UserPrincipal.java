package com.fileproc.auth.filter;

import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * JWT 解析后存入 SecurityContext 的用户 Principal
 * P1-10：增加 realName 字段，避免 NoticeService 等在事务中额外查库
 */
@Getter
public class UserPrincipal implements UserDetails {

    private final String userId;
    private final String tenantId;
    private final String roleId;
    private final String realName;
    private final List<? extends GrantedAuthority> authorities;

    public UserPrincipal(String userId, String tenantId, String roleId, String realName,
                         List<? extends GrantedAuthority> authorities) {
        this.userId = userId;
        this.tenantId = tenantId;
        this.roleId = roleId;
        this.realName = realName;
        this.authorities = authorities;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() { return null; }

    @Override
    public String getUsername() { return userId; }

    @Override
    public boolean isAccountNonExpired() { return true; }

    @Override
    public boolean isAccountNonLocked() { return true; }

    @Override
    public boolean isCredentialsNonExpired() { return true; }

    @Override
    public boolean isEnabled() { return true; }
}
