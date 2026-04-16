# Build stage
FROM maven:3.9.6-eclipse-temurin-21-alpine AS build
WORKDIR /app

# Copy the entire project context
COPY . .

# Build core first, then manager
RUN mvn clean install -DskipTests -pl backend-core -am -B
RUN mvn clean package -DskipTests -pl backend-manager -B

# Run stage
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/backend-manager/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
