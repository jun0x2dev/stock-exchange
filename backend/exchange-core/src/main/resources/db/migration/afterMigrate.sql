-- 기동할 때마다 앱 계정의 비밀번호를 환경 변수 값으로 맞춘다. 비밀번호를 마이그레이션 파일에 두지 않는다.
ALTER ROLE exchange_app PASSWORD '${apppassword}';
