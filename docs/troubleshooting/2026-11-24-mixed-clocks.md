# 앱과 DB의 시계가 섞여 기록 순서가 뒤집힐 수 있음

## 증상

취소 처리 시간을 `updated_at - cancel_requested_at`으로 재니 1~3초가 나왔다. 실제로는 약 50ms였다. 잔고와 원장 금액에는 영향이 없었다.

## 원인

주문 접수·취소 요청 시각은 DB 시계, 체결·원장·변경 시각은 앱 시계로 남겼다. 앱(Windows)과 DB(WSL)의 시계가 0.7~2.3초 어긋나 있었다. 어긋난 방향이 반대였다면 주문보다 체결이 먼저인 기록이 남는다.

## 해결

- 모든 시각을 DB의 `clock_timestamp()`로 남긴다(V4, 엔티티는 `insertable = false`). 주문의 `updated_at`은 트리거가 바꾼다.
- 재발 방지: `TradingTest.timestampsUseDatabaseClock`
- 명령 순서와 시간 우선은 원래 DB 시각과 엔진 순번만 썼으므로 영향을 받지 않았다.
