# 1단계: 빌드 (Gradle로 실행 가능한 jar 만들기)
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# 의존성 먼저 받아 두기 (소스만 바뀌면 이 단계는 캐시를 재사용해서 빌드가 빨라짐)
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon > /dev/null 2>&1 || true

COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

# 2단계: 실행 (JDK 대신 JRE만 담아 이미지를 가볍게)
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
