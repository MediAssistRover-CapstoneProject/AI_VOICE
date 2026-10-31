FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src src
RUN --mount=type=cache,id=s/7c61045a-bacd-4265-a22e-75000517ebd4-/root/.m2,target=/root/.m2 mvn -B --no-transfer-progress -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 appuser \
    && mkdir -p /app/data/map-artifacts /app/data/speech /app/data/knowledge \
    && chown -R appuser:appuser /app/data
COPY --from=build /workspace/target/backend-*.jar app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
