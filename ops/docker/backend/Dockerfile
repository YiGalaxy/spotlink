FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace/backend
COPY backend/pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY backend/src src
RUN mvn -B -ntp -DskipTests package
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 spotlink
COPY --from=build /workspace/backend/target/*.jar /app/spotlink-backend.jar
USER 10001
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/spotlink-backend.jar"]
