-- 부하 측정용 사용자 3명과 계좌 30개를 만든다. 별도 DB에서만 실행한다.
-- 잔고는 원장과 맞도록 bench-deposit.mjs가 API로 입금·입고한다. 비밀번호는 체험용 demo와 같다.
INSERT INTO users (login_id, password_hash, role)
SELECT 'bench' || i, (SELECT password_hash FROM users WHERE login_id = 'demo'), 'CUSTOMER' FROM generate_series(1, 3) i;
INSERT INTO account (user_id, account_type)
SELECT u.user_id, 'CUSTOMER' FROM users u, generate_series(1, 10) WHERE u.login_id LIKE 'bench%';
