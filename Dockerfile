FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
RUN mvn --batch-mode --no-transfer-progress package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/target/bateconta-0.1.0.jar /app/bateconta.jar
ENV BATECONTA_ADDRESS=0.0.0.0
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=55.0", "-jar", "/app/bateconta.jar"]
