# ---------------------------------------------------------------- build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# ---------------------------------------------------------------- runtime
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app && apk add --no-cache wget
WORKDIR /app
COPY --from=build /app/target/erpadv-backend.jar app.jar
USER app

ENV SERVER_PORT=8085 \
    SPRING_PROFILES_ACTIVE=json \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8085
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8085/actuator/health/liveness || exit 1

# Nenhuma credencial é copiada para a imagem: a service account é montada em tempo de execução.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
