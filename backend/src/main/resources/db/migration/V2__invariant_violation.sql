-- 불변식 I1~I7 대사. 테스트와 대사 배치가 같은 뷰를 쓰며, 한 행이라도 나오면 정합성이 깨진 것이다.
CREATE VIEW invariant_violation AS
WITH cash AS (
    SELECT account_id, SUM(cash_delta) AS cash, SUM(reserved_delta) AS reserved
      FROM cash_ledger GROUP BY account_id
), stock_ledger AS (
    SELECT l.account_id, l.symbol, SUM(l.quantity_delta) AS quantity, SUM(l.reserved_delta) AS reserved
      FROM position_ledger l JOIN account a ON a.account_id = l.account_id AND a.account_type = 'CUSTOMER'
     GROUP BY l.account_id, l.symbol
), filled AS (
    SELECT order_id, SUM(quantity) AS quantity
      FROM (SELECT buy_order_id AS order_id, quantity FROM execution
            UNION ALL
            SELECT sell_order_id, quantity FROM execution) e
     GROUP BY order_id
), active AS (
    SELECT * FROM orders WHERE status IN ('ACCEPTED', 'OPEN', 'PARTIALLY_FILLED')
), buying AS (
    SELECT account_id, SUM(reserved_amount) AS reserved FROM active WHERE side = 'BUY' GROUP BY account_id
), selling AS (
    SELECT account_id, symbol, SUM(quantity - filled_quantity) AS reserved
      FROM active WHERE side = 'SELL' GROUP BY account_id, symbol
)
SELECT 'I1' AS invariant, 'account=' || account_id AS detail
  FROM account WHERE account_type = 'CUSTOMER' AND NOT (0 <= reserved_cash AND reserved_cash <= cash_balance)
UNION ALL
SELECT 'I1', 'account=' || account_id || ' symbol=' || symbol
  FROM position WHERE NOT (0 <= reserved_quantity AND reserved_quantity <= quantity)
UNION ALL
SELECT 'I2', 'account=' || a.account_id
  FROM account a LEFT JOIN cash c USING (account_id)
 WHERE a.account_type = 'CUSTOMER'
   AND (a.cash_balance <> COALESCE(c.cash, 0) OR a.reserved_cash <> COALESCE(c.reserved, 0))
UNION ALL
SELECT 'I3', 'account=' || account_id || ' symbol=' || symbol
  FROM position p FULL JOIN stock_ledger l USING (account_id, symbol)
 WHERE COALESCE(p.quantity, 0) <> COALESCE(l.quantity, 0)
    OR COALESCE(p.reserved_quantity, 0) <> COALESCE(l.reserved, 0)
UNION ALL
SELECT 'I4', 'cash journal=' || journal_id
  FROM cash_ledger GROUP BY journal_id HAVING SUM(cash_delta) <> 0
UNION ALL
SELECT 'I4', 'stock journal=' || journal_id || ' symbol=' || symbol
  FROM position_ledger GROUP BY journal_id, symbol HAVING SUM(quantity_delta) <> 0
UNION ALL
SELECT 'I5', 'order=' || o.order_id
  FROM orders o LEFT JOIN filled f USING (order_id)
 WHERE o.filled_quantity <> COALESCE(f.quantity, 0) OR o.filled_quantity > o.quantity
UNION ALL
SELECT 'I6', 'account=' || a.account_id
  FROM account a LEFT JOIN buying b USING (account_id)
 WHERE a.account_type = 'CUSTOMER' AND a.reserved_cash <> COALESCE(b.reserved, 0)
UNION ALL
SELECT 'I7', 'account=' || account_id || ' symbol=' || symbol
  FROM position p FULL JOIN selling s USING (account_id, symbol)
 WHERE COALESCE(p.reserved_quantity, 0) <> COALESCE(s.reserved, 0);
