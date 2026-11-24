package com.exchange.domain.user.dto;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.exchange.domain.user.entity.Role;
import com.exchange.domain.user.entity.User;

/** 세션에 저장하는 로그인 사용자. 인증이 끝나면 비밀번호 해시를 지운다. */
public final class LoginUser implements UserDetails, CredentialsContainer {

	private final Long userId;
	private final String loginId;
	private final Role role;
	private final boolean locked;
	private String passwordHash;

	// 엔티티에서 필요한 값만 옮긴다.
	private LoginUser(User user) {
		this.userId = user.getUserId();
		this.loginId = user.getLoginId();
		this.role = user.getRole();
		this.locked = user.isLocked(Instant.now());
		this.passwordHash = user.getPasswordHash();
	}

	/** 사용자 엔티티에서 만든다. */
	public static LoginUser from(User user) {
		return new LoginUser(user);
	}

	/** 사용자 ID. */
	public Long userId() {
		return userId;
	}

	/** 역할. */
	public Role role() {
		return role;
	}

	@Override
	public Collection<? extends GrantedAuthority> getAuthorities() {
		return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
	}

	@Override
	public String getPassword() {
		return passwordHash;
	}

	@Override
	public String getUsername() {
		return loginId;
	}

	@Override
	public boolean isAccountNonLocked() {
		return !locked;
	}

	@Override
	public void eraseCredentials() {
		passwordHash = null;
	}
}
