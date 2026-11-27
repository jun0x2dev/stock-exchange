package com.exchange.domain.fds.repository;

import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import com.exchange.domain.fds.entity.FdsAlert;

/** 이상거래 경보 저장소. 수정·삭제 메서드를 두지 않는다. */
public interface FdsAlertRepository extends Repository<FdsAlert, Long> {

	/** 경보와 계좌 주인의 아이디. */
	record Row(FdsAlert alert, String owner) {
	}

	/**
	 * 허수성 호가를 찾아 경보로 남기고, 새로 남긴 수를 반환한다. 이미 경보한 주문은 건너뛴다.
	 *
	 * <p>최근 취소된 주문 중 다음을 모두 만족하는 것: 체결 비율 이하, 짧은 시간 안에 취소, 살아 있는 동안 같은 계좌가
	 * 반대편에서 체결했고 그 수량의 배수 이상, 들어온 순간 같은 쪽 호가 잔량에서 차지한 비율 이상. 기준값은 프로젝트 정책이다.
	 */
	@Modifying
	@Query(nativeQuery = true, value = """
		WITH candidate AS (
		    SELECT o.order_id, o.account_id, o.symbol, o.side, o.quantity, o.filled_quantity, o.created_at,
		           o.cancel_requested_at
		      FROM orders o
		     WHERE o.status = 'CANCELED'
		       AND o.updated_at > clock_timestamp() - make_interval(mins => :lookbackMinutes)
		       AND o.cancel_requested_at IS NOT NULL
		       AND o.cancel_requested_at - o.created_at <= make_interval(secs => :maxLifetimeSeconds)
		       AND o.filled_quantity * 100 <= o.quantity * :maxFillPercent
		       AND NOT EXISTS (SELECT 1 FROM fds_alert a WHERE a.rule = 'SPOOFING' AND a.dedupe_key = 'order:' || o.order_id)
		), opposite AS (
		    -- 주문이 살아 있는 동안 같은 계좌가 반대편에서 체결한 수량. 체결은 매수·매도 주문 번호로 각각 찾는다.
		    SELECT c.order_id, SUM(e.quantity) AS quantity
		      FROM candidate c
		      JOIN orders o ON o.account_id = c.account_id AND o.symbol = c.symbol AND o.side <> c.side
		       AND o.created_at <= c.cancel_requested_at
		      JOIN execution e ON e.buy_order_id = o.order_id
		     WHERE c.side = 'SELL' AND e.executed_at BETWEEN c.created_at AND c.cancel_requested_at
		     GROUP BY c.order_id
		    UNION ALL
		    SELECT c.order_id, SUM(e.quantity)
		      FROM candidate c
		      JOIN orders o ON o.account_id = c.account_id AND o.symbol = c.symbol AND o.side <> c.side
		       AND o.created_at <= c.cancel_requested_at
		      JOIN execution e ON e.sell_order_id = o.order_id
		     WHERE c.side = 'BUY' AND e.executed_at BETWEEN c.created_at AND c.cancel_requested_at
		     GROUP BY c.order_id
		), suspect AS (
		    -- 시장 조성자는 거둔 호가보다 반대편에서 더 많이 거래한다. 스푸핑은 보여 준 수량이 실제 거래보다 훨씬 크다.
		    SELECT c.*, p.quantity AS opposite_quantity
		      FROM candidate c JOIN opposite p ON p.order_id = c.order_id
		     WHERE c.quantity >= p.quantity * :fakeToReal
		)
		INSERT INTO fds_alert (rule, dedupe_key, account_id, symbol, order_id, evidence)
		SELECT 'SPOOFING', 'order:' || s.order_id, s.account_id, s.symbol, s.order_id,
		       format('%s %s주(그 순간 같은 쪽 잔량의 %s%%), %s초 만에 체결 %s주로 취소, 그사이 반대편 %s주 체결',
		              CASE s.side WHEN 'BUY' THEN '매수' ELSE '매도' END, s.quantity,
		              round(100.0 * s.quantity / (s.quantity + d.quantity)),
		              round(EXTRACT(EPOCH FROM s.cancel_requested_at - s.created_at)::numeric, 1),
		              s.filled_quantity, s.opposite_quantity)
		  FROM suspect s
		  -- 들어온 순간 같은 쪽에 살아 있던 다른 주문의 수량. 그 전에 들어왔고, 아직 남았거나 그 뒤에 끝난 주문이다.
		  -- ponytail: 하루 넘게 걸려 있던 주문은 세지 않는다. 그만큼 잔량을 적게 보아 경보가 늘 수 있다.
		  CROSS JOIN LATERAL (
		      SELECT COALESCE(SUM(o.quantity), 0) AS quantity
		        FROM orders o
		       WHERE o.symbol = s.symbol AND o.side = s.side AND o.order_id <> s.order_id
		         AND o.created_at < s.created_at AND o.created_at > s.created_at - interval '1 day'
		         AND (o.status IN ('ACCEPTED', 'OPEN', 'PARTIALLY_FILLED') OR o.updated_at > s.created_at)
		  ) d
		 WHERE s.quantity * 100 >= (s.quantity + d.quantity) * :minDepthSharePercent
		ON CONFLICT (rule, dedupe_key) DO NOTHING
		""")
	int detectSpoofing(int lookbackMinutes, int maxLifetimeSeconds, int maxFillPercent, int fakeToReal,
		int minDepthSharePercent);

	/** 커서보다 오래된 경보를 최신순으로, 계좌 주인의 아이디와 함께 조회한다. */
	@Query("""
		SELECT new com.exchange.domain.fds.repository.FdsAlertRepository$Row(a, u.loginId)
		  FROM FdsAlert a JOIN Account ac ON ac.accountId = a.accountId LEFT JOIN User u ON u.userId = ac.userId
		 WHERE a.alertId < :cursor ORDER BY a.alertId DESC
		""")
	List<Row> findRows(long cursor, Limit limit);
}
