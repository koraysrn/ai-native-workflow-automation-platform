# Build stage
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY backend/pom.xml pom.xml
COPY backend/checkstyle.xml checkstyle.xml
COPY backend/common/pom.xml common/pom.xml
COPY backend/identity/pom.xml identity/pom.xml
RUN mvn -B -q -DskipTests dependency:go-offline
COPY backend/common/src common/src
COPY backend/identity/src identity/src
RUN mvn -B -q -DskipTests package

# Runtime stage
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/identity/target/awap-identity-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
