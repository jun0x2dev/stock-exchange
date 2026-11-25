package com.exchange.domain.user.entity;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 로그인 사용자. */
@Entity
@Getter
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long userId;

	private String loginId;

	private String passwordHash;

	@Enumerated(EnumType.STRING)
	private Role role;

	private int failedCount;

	private Instant lockedUntil;

	/** 비밀번호 해시로 사용자를 만든다. 평문 비밀번호를 받지 않는다. */
	public static User create(String loginId, String passwordHash, Role role) {
		User user = new User();
		user.loginId = loginId;
		user.passwordHash = passwordHash;
		user.role = role;
		return user;
	}

	/** 로그인 실패로 잠긴 상태인지 확인한다. */
	public boolean isLocked(Instant now) {
		return lockedUntil != null && lockedUntil.isAfter(now);
	}
}
