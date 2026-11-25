# 화면, API 서버(core), 매칭 엔진을 한 번에 빌드한다. 기반 이미지는 태그가 바뀌어도 같은 내용이 되도록 digest로 고정한다.
FROM node:24-bookworm-slim@sha256:873f586f3794f52281bd039bf02c048a0be08fde619338f9941bad1eb6b34530 AS web
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --ignore-scripts
COPY frontend/ ./
RUN npm run build

FROM eclipse-temurin:21-jdk-alpine@sha256:c07bb82e004a166b5d3d63b84ad70e2e822f228505e2e0c0b8dfdc2890d22f13 AS build
WORKDIR /src/backend
COPY backend/ ./
COPY --from=web /src/frontend/dist /src/frontend/dist
RUN --mount=type=cache,target=/root/.gradle \
    sh ./gradlew :exchange-core:bootJar :exchange-engine:installDist :exchange-feed:installDist --no-daemon --console=plain

FROM eclipse-temurin:21-jre-alpine@sha256:5d7689f47d670aca766b9503a70bccc2d73b4eb9221ae8f714ae958313c4ce70 AS core
RUN adduser -D -H app && install -d -o app /var/lib/exchange
USER app
COPY --from=build /src/backend/exchange-core/build/libs/exchange-core-0.0.1-SNAPSHOT.jar /app/core.jar
EXPOSE 8080
HEALTHCHECK --start-period=120s --interval=10s --timeout=5s --retries=6 \
    CMD wget -qO- http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-jar", "/app/core.jar"]

FROM eclipse-temurin:21-jre-alpine@sha256:5d7689f47d670aca766b9503a70bccc2d73b4eb9221ae8f714ae958313c4ce70 AS engine
RUN adduser -D -H app
USER app
COPY --from=build /src/backend/exchange-engine/build/install/exchange-engine/lib /app/lib
HEALTHCHECK NONE
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-cp", "/app/lib/*", "com.exchange.engine.app.EngineApplication"]

FROM eclipse-temurin:21-jre-alpine@sha256:5d7689f47d670aca766b9503a70bccc2d73b4eb9221ae8f714ae958313c4ce70 AS feed
RUN adduser -D -H app
USER app
COPY --from=build /src/backend/exchange-feed/build/install/exchange-feed/lib /app/lib
HEALTHCHECK NONE
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60", "-cp", "/app/lib/*", "com.exchange.feed.FeedApplication"]
