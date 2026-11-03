# Tests run through "mvnw verify", so the image build only packages the application.
FROM maven:3.9.13-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-noble
RUN useradd --system --no-create-home docchat
# A new named volume copies this directory's owner, so the non-root user can write to it.
RUN install -d -o docchat /var/lib/docchat/storage
WORKDIR /app
COPY --from=build /workspace/target/docchat-backend-*.jar app.jar
USER docchat
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
