FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml ./
COPY .mvn .mvn
COPY mvnw ./
COPY src src
RUN ./mvnw -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/fintrack-backend-0.1.0.jar app.jar
ENV JAVA_OPTS=""
EXPOSE 10000
CMD ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
