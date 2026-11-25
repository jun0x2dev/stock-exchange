# DB 계정을 나눈 뒤 Docker에서 core가 unhealthy

## 증상

테스트와 로컬 실행은 모두 통과했지만, Docker로 전체를 띄우면 화면은 응답하는데 `/actuator/health`가 503이었다. core의 상태를 기다리는 엔진과 시세가 시작되지 않아 체결이 멈춘다. 돈·주식 정합성에는 영향이 없다.

## 원인

Spring Boot의 헬스 체크는 모든 `DataSource` 빈을 검사한다. 마이그레이션 전용 소유자 연결도 빈이라 함께 검사했고, core에 소유자 비밀번호를 주지 않자 실패했다. 테스트는 소유자 비밀번호가 있어 드러나지 않았다.

## 해결

- 마이그레이션을 따로 실행하면(`spring.flyway.enabled=false`) 소유자 연결을 만들지 않는다.
- `DataSourceConfigTest`가 확인한다. 구성이 달라지는 변경은 Docker 전체 실행까지 확인한다.
