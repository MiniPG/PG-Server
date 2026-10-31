# 빌드 단계는 빌드하는 머신의 아키텍처(GitHub Actions는 x86)에서 그대로 실행 → 에뮬레이션 없이 빠르게 jar 생성
# jar는 아키텍처와 무관하므로, 실행 단계(arm64 JRE)에 복사만 하면 된다
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /app

COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon > /dev/null 2>&1 || true

COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
