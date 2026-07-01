FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/file-compression-service-0.0.1-SNAPSHOT.jar app.jar
RUN mkdir -p data/inbox data/outbox data/processed data/dlq data/db
ENTRYPOINT ["java", "-jar", "app.jar"]
