# Docker가 유휴 상태에서 깨어나지 못해 테스트가 실패

## 증상

- Testcontainers가 `Could not find a valid Docker environment`로 실패하고, `docker version`은 500 오류를 낸다.
- 재시작하면 `wsl.exe -l -v --all: context deadline exceeded`가 뜬다. 돈·주식 정합성에는 영향이 없다.

## 원인

Docker Desktop의 Resource Saver가 5분 유휴 후 엔진을 동결하는 도중 멈췄다. 로그에 `idle: paused` 다음 `pausing VM: ... context deadline exceeded`가 남고, VM 로그는 `freezing cgroup`에서 끝난다. WSL은 3.0.1, Docker Engine은 27.4.0이었다.

## 해결

- WSL을 재시작해야 풀린다(재부팅이 확실하다).
- Resource Saver를 끄면 재발하지 않을 것으로 보이며, 아직 확인하지 않았다.
