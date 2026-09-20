FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

# Copy build files first — Docker caches this layer so deps only re-download when build files change
COPY gradle gradle
COPY gradlew .
COPY build.gradle .
COPY settings.gradle .
COPY shared-domain/build.gradle shared-domain/build.gradle
COPY brain-engine-core/build.gradle brain-engine-core/build.gradle
COPY genesis-brands/build.gradle genesis-brands/build.gradle
RUN ./gradlew dependencies --no-daemon -q

# Copy source and build
COPY shared-domain shared-domain
COPY brain-engine-core brain-engine-core
COPY genesis-brands genesis-brands
RUN ./gradlew :genesis-brands:bootJar --no-daemon -q

# Playwright Chromium requires glibc — switch from Alpine to Ubuntu jammy for the runtime.
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Tell Playwright where to store browser binaries (picked up by both the installer and the app)
ENV PLAYWRIGHT_BROWSERS_PATH=/app/pw-browsers

COPY --from=build /workspace/genesis-brands/build/libs/genesis-brands-0.0.1-SNAPSHOT.jar app.jar
COPY knowledge/layer1/modules knowledge/layer1/modules

# Install Chromium and all its system dependencies via the Playwright CLI bundled inside
# the fat JAR. unzip extracts the nested BOOT-INF/lib/ JARs so we can call CLI directly;
# `--with-deps` handles apt-get install of Chromium's glibc deps in a single step.
RUN apt-get update && apt-get install -y --no-install-recommends unzip ca-certificates \
  && mkdir -p /tmp/pwinstall \
  && unzip -q app.jar "BOOT-INF/lib/*" -d /tmp/pwinstall \
  && java -cp "/tmp/pwinstall/BOOT-INF/lib/*" com.microsoft.playwright.CLI install --with-deps chromium \
  && rm -rf /tmp/pwinstall /var/lib/apt/lists/*

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
