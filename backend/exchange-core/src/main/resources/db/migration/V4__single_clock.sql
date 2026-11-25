-- 앱과 DB의 시계가 어긋나면 주문보다 체결이 먼저인 것처럼 기록될 수 있다. 모든 시각을 DB 시계로 남긴다.
ALTER TABLE execution ALTER COLUMN executed_at SET DEFAULT clock_timestamp();
ALTER TABLE cash_ledger ALTER COLUMN created_at SET DEFAULT clock_timestamp();
ALTER TABLE position_ledger ALTER COLUMN created_at SET DEFAULT clock_timestamp();

CREATE FUNCTION set_updated_at() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at := clock_timestamp();
    RETURN NEW;
END $$;
CREATE TRIGGER set_updated_at BEFORE UPDATE ON orders FOR EACH ROW EXECUTE FUNCTION set_updated_at();
