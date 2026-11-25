package com.exchange.domain.account.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import com.exchange.domain.account.entity.Account;

import jakarta.persistence.LockModeType;

/** 계좌 저장소. */
public interface AccountRepository extends JpaRepository<Account, Long> {

	/** 사용자의 계좌 목록. */
	List<Account> findByUserIdOrderByAccountId(Long userId);

	/** 사용자의 계좌가 맞는지 확인한다. */
	boolean existsByAccountIdAndUserId(Long accountId, Long userId);

	/** 입출금의 상대 계정 ID. */
	@Query("SELECT a.accountId FROM Account a WHERE a.accountType = com.exchange.domain.account.entity.AccountType.EXTERNAL")
	long findExternalId();

	/** 주문 가능 금액이 충분할 때만 예약한다. 확인과 변경을 나누면 동시 주문에서 초과 예약된다. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		UPDATE Account a SET a.reservedCash = a.reservedCash + :amount
		 WHERE a.accountId = :accountId AND a.cashBalance - a.reservedCash >= :amount
		""")
	int reserve(long accountId, long amount);

	/** 고객 계좌에 입금한다. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		UPDATE Account a SET a.cashBalance = a.cashBalance + :amount
		 WHERE a.accountId = :accountId
		   AND a.accountType = com.exchange.domain.account.entity.AccountType.CUSTOMER
		""")
	int deposit(long accountId, long amount);

	/** 입금을 되돌린다. 쓰지 않았고 주문에 묶이지 않은 금액이 충분할 때만 뺀다. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
		UPDATE Account a SET a.cashBalance = a.cashBalance - :amount
		 WHERE a.accountId = :accountId AND a.cashBalance - a.reservedCash >= :amount
		""")
	int withdraw(long accountId, long amount);

	/** 고객 계좌인지 확인한다. */
	@Query("""
		SELECT COUNT(a) > 0 FROM Account a WHERE a.accountId = :accountId
		   AND a.accountType = com.exchange.domain.account.entity.AccountType.CUSTOMER
		""")
	boolean isCustomer(long accountId);

	/** 데드락을 막으려고 계좌 ID 순으로 잠근다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT a FROM Account a WHERE a.accountId IN :ids ORDER BY a.accountId")
	List<Account> lockAll(Collection<Long> ids);
}
