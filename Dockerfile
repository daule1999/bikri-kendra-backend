# bikri-backend — modular monolith image (replaces six service images)
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
COPY platform/pom.xml platform/
COPY auth/pom.xml auth/
COPY user/pom.xml user/
COPY inventory/pom.xml inventory/
COPY sales/pom.xml sales/
COPY billing/pom.xml billing/
COPY printer/pom.xml printer/
COPY app/pom.xml app/
RUN ./mvnw -q dependency:go-offline -pl app -am || true

COPY . .
RUN ./mvnw -q clean package -DskipTests -pl app -am

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-Duser.timezone=Asia/Kolkata", "-XX:MaxRAMPercentage=70", "-jar", "app.jar"]
