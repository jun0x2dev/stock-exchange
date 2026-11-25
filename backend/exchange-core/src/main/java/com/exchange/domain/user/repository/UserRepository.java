package com.exchange.domain.user.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.exchange.domain.user.entity.User;

/** 사용자 저장소. */
public interface UserRepository extends JpaRepository<User, Long> {

	/** 로그인 아이디로 찾는다. */
	Optional<User> findByLoginId(String loginId);

	/** 실패 횟수를 올리고, 한도에 닿으면 잠근다. 동시에 실패해도 누락되지 않도록 한 번의 UPDATE로 처리한다. */
	@Modifying
	@Query(nativeQuery = true, value = """
		UPDATE users
		   SET locked_until = CASE WHEN failed_count + 1 >= :maxFailures
		                           THEN now() + make_interval(mins => :lockMinutes) ELSE locked_until END,
		       failed_count = CASE WHEN failed_count + 1 >= :maxFailures THEN 0 ELSE failed_count + 1 END
		 WHERE login_id = :loginId
		""")
	int recordFailure(String loginId, int maxFailures, int lockMinutes);

	/** 로그인에 성공하면 실패 기록을 지운다. */
	@Modifying
	@Query(nativeQuery = true,
		value = "UPDATE users SET failed_count = 0, locked_until = NULL WHERE user_id = :userId AND failed_count > 0")
	int clearFailures(Long userId);
}
