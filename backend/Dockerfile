FROM maven:3.9.9-eclipse-temurin-17-alpine AS build
WORKDIR /app
COPY pom.xml ./
RUN mvn -B -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre-alpine
RUN apk add --no-cache curl
WORKDIR /app
COPY --from=build /app/target/campusclaw-backend-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

