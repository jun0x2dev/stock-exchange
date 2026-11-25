# ledger

## 정의

현금·주식 복식부기 원장. 한 거래의 행은 같은 전표 번호로 묶는다.

## 주요 상태/흐름

- 잔고를 바꾸는 트랜잭션 안에서만 기록한다(`Propagation.MANDATORY`).
- 애플리케이션 DB 계정(`exchange_app`)에는 추가와 조회 권한만 있다. 소유자 계정의 실수는 트리거가 막는다.
- 잘못된 입금·입고는 [정정](../adjustment/README.md)으로 반대 전표(`REVERSAL`)를 추가한다.

## 관련 불변식

I2, I3, I4. 대사 뷰 `invariant_violation`에 한 행이라도 나오면 정합성이 깨진 것이다.
