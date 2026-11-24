# order

## 정의

지정가 주문의 접수, 예약, 취소 요청, 조회.

## 주요 상태/흐름

`ACCEPTED`(저장·예약) → `OPEN`(호가창) → `PARTIALLY_FILLED` → `FILLED`, 어느 단계에서든 `CANCELED`.

- 멱등키를 먼저 `INSERT ... ON CONFLICT`로 선점하고 예약한다. 예약에 실패하면 전부 롤백된다.
- 취소는 `cancel_requested_at`만 남긴다. 엔티티가 이 컬럼을 덮어쓰지 않도록 `updatable = false`로 둔다.

## 관련 불변식

I5, I6, I7. 매수 예약금이 남은 수량과 맞는지는 DB `CHECK`로도 막는다.
