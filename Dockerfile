FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

# Copy build files first — Docker caches this layer so deps only re-download when build files change
COPY gradle gradle
COPY gradlew .
COPY build.gradle .
COPY settings.gradle .
RUN ./gradlew dependencies --no-daemon -q

# Copy source and build
COPY src src
RUN ./gradlew bootJar --no-daemon -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
COPY agents agents
COPY knowledge/layer1/modules knowledge/layer1/modules
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
