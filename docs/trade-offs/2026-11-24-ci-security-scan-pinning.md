# CI 보안 검사 도구의 실행 방식

## 선택지

1. 각 도구의 공식 GitHub Action을 버전 태그로 사용
2. 공식 GitHub Action을 커밋 SHA로 고정
3. 공식 컨테이너 이미지를 digest로 고정해 직접 실행

## 선택

3번. 꼭 필요한 액션(`checkout`, `setup-java`)만 커밋 SHA로 고정해 쓰고, gitleaks·Semgrep·Trivy는 이미지 digest로 고정해 실행한다.
간접 의존성은 Gradle lockfile로, Gradle 배포본은 SHA-256으로 고정한다.

## 이유

- 태그는 나중에 다른 내용을 가리키게 바뀔 수 있다. 2026년 3월 Trivy 사고에서는 `trivy-action`의 태그 대부분이 악성 코드로 덮어써졌다([GHSA-69fq-xp46-6x23](https://github.com/aquasecurity/trivy/security/advisories/GHSA-69fq-xp46-6x23)).
- 이미지를 직접 실행하면 로컬에서도 CI와 같은 도구로 검사할 수 있다.
- 출시 직후 버전은 피하고 2주 이상 지난 버전을 골랐다.
- Trivy는 Gradle 빌드 파일만으로 간접 의존성을 알 수 없어 lockfile이 필요하다.
- 감수한 점: 버전이 자동으로 올라가지 않으므로 직접 갱신해야 한다. 의존성을 바꾸면 `backend`에서 `./gradlew dependencies --write-locks`를 실행해야 한다.
- 한계: Semgrep 규칙과 Trivy 취약점 DB는 실행할 때 내려받으므로 고정되지 않는다.
