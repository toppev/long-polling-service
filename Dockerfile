# docker build . -t data-polling

FROM gradle:jdk8 as builder
ENV GRADLE_SRC=/home/gradle/src/
WORKDIR $GRADLE_SRC

# Cache depenencies
COPY --chown=gradle:gradle build.gradle.kts settings.gradle.kts gradlew $GRADLE_SRC
COPY --chown=gradle:gradle gradle $GRADLE_SRC/gradle
RUN gradle build --no-daemon || return 0

# Build
COPY --chown=gradle:gradle . $GRADLE_SRC
RUN gradle build --no-daemon

# Run
FROM openjdk:8-alpine
ENV APP_HOME=/app/
WORKDIR $APP_HOME
# Only copy the shadow/fat jar
COPY --from=builder /home/gradle/src/build/libs/*all.jar $APP_HOME/data-polling.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "data-polling.jar"]