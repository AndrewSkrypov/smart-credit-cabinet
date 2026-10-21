# syntax=docker/dockerfile:1.7
# One image recipe for every backend service: docker build --build-arg SERVICE=credit ...
#
# JAR_SOURCE=gradle   (default, local docker compose) compiles the service inside the build.
# JAR_SOURCE=prebuilt (CI) takes services/$SERVICE/build/libs/app.jar that the build job
#                     already compiled and tested, so the image contains exactly that jar.
ARG JAR_SOURCE=gradle

FROM gradle:8.10.2-jdk21 AS jar-gradle
ARG SERVICE
WORKDIR /workspace
COPY --chown=gradle:gradle . .
RUN --mount=type=cache,target=/home/gradle/.gradle,sharing=locked \
    ./gradlew :services:${SERVICE}:bootJar --no-daemon --no-watch-fs \
 && cp services/${SERVICE}/build/libs/app.jar /app.jar

FROM scratch AS jar-prebuilt
ARG SERVICE
COPY services/${SERVICE}/build/libs/app.jar /app.jar

FROM jar-${JAR_SOURCE} AS jar

# Split the fat jar into layers (dependencies change rarely, application code often),
# so a code-only change pushes and pulls a few hundred KB instead of the whole image.
FROM eclipse-temurin:21-jre-jammy AS extract
WORKDIR /extract
COPY --from=jar /app.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination layers

# Slim runtime: the JRE, curl for the health checks and one font Java needs to draw text.
# Keep it that small: every extra package is one more thing the image scanner can flag.
# Runs as an unprivileged user (uid 10001), never as root, in compose and in the cluster.
# Paths are copied in the order they change: dependencies first, rarely touched,
# Our own application code last, so a code-only release ships a few hundred KB.
# Vulnerabilities in these layers stop the pipeline (Trivy): bump the base tags then.
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl fonts-dejavu-core \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --home-dir /app app \
 && mkdir -p /app/uploads && chown app /app/uploads
WORKDIR /app
COPY --from=extract /extract/layers/dependencies/ ./
COPY --from=extract /extract/layers/spring-boot-loader/ ./
COPY --from=extract /extract/layers/snapshot-dependencies/ ./
COPY --from=extract /extract/layers/application/ ./
USER 10001
EXPOSE 8080
# Size the heap from the container memory limit instead of a hard-coded -Xmx.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
