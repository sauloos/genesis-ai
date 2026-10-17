FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /workspace

# az acr build keeps no layer cache between builds, so there's no separate dependency
# layer — copy the build and sources, then build. settings.gradle auto-discovers themes/
# and os/agents/, so new agent modules need no edit here.
COPY gradle gradle
COPY gradlew .
COPY build.gradle .
COPY settings.gradle .
COPY themes themes

COPY shared-domain shared-domain
COPY os os
COPY genesis-brands genesis-brands
RUN ./gradlew :genesis-brands:bootJar --no-daemon -q

# Playwright Chromium requires glibc — switch from Alpine to Ubuntu jammy for the runtime.
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Tell Playwright where to store browser binaries (picked up by both the installer and the app)
ENV PLAYWRIGHT_BROWSERS_PATH=/app/pw-browsers

COPY --from=build /workspace/genesis-brands/build/libs/genesis-brands-0.0.1-SNAPSHOT.jar app.jar
COPY knowledge/layer1/modules knowledge/layer1/modules
COPY ingestion ingestion

# Install Chromium (for brand-book PDF), Python 3 + pip (for ingestion pipeline), and
# all Python ingestion dependencies. `--with-deps` handles Chromium's glibc apt deps.
RUN apt-get update && apt-get install -y --no-install-recommends unzip ca-certificates python3 python3-pip \
  && mkdir -p /tmp/pwinstall \
  && unzip -q app.jar "BOOT-INF/lib/*" -d /tmp/pwinstall \
  && java -cp "/tmp/pwinstall/BOOT-INF/lib/*" com.microsoft.playwright.CLI install --with-deps chromium \
  && rm -rf /tmp/pwinstall /var/lib/apt/lists/* \
  && pip3 install --no-cache-dir -r ingestion/requirements.txt

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
