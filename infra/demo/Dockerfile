# Hackathon demo image only. This is NOT the production API image the AWS plan
# specifies (deterministic build, non-root, reproducible layers) -- that is AWS
# plan Task 3 and has not been executed. This image exists so the whole service
# can run on the LLM laptop for a demo, with no cloud spend.
#
# The jar is built outside this context (`./gradlew :api:bootJar`) and copied in,
# so the laptop never needs a JDK or the Gradle dependency graph.
FROM eclipse-temurin:21-jre@sha256:a80c51f2d09a3e7e00d521f1c817bbceb6b3be94109b4a784d46078099882dda

RUN useradd --system --create-home --uid 10001 --shell /usr/sbin/nologin nextvisit
USER 10001:10001
WORKDIR /app
COPY --chown=10001:10001 api.jar /app/api.jar

EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/api.jar"]
