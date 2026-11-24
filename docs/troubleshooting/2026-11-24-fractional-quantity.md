# 1.5주 주문이 1주로 접수됨

## 증상

`"quantity": 1.5`인 주문이 400이 아니라 201로 접수됐다. 사용자가 보낸 내용과 다른 주문이 만들어진다. 예약과 원장은 1주 기준으로 일치했다.

## 원인

Jackson은 기본값으로 소수를 정수로 잘라 읽는다. 같은 이유로 `"side": 1`도 enum 순서로 해석돼 매도로 접수될 수 있었다.

## 해결

- `accept-float-as-int: false`, `fail-on-numbers-for-enums: true`로 거부한다.
- 재발 방지: `AccessControlTest.invalidRequests`
