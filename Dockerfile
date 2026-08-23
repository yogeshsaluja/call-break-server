# Runtime-only image for the Call Break server.
#
# The application distribution is built ON THE HOST first, because the shared
# engine/ai/protocol source lives outside this project (in ../Call-Break) and is not
# reachable from Docker's build context. Build it with:
#
#     ./gradlew :server:installDist
#
# then build this image. This keeps the image small (just a JRE + the app) and preserves
# the single-source-of-truth design. When you later want a fully hermetic in-container
# build, vendor/submodule the shared source into this project and switch to a multi-stage
# Gradle build.
FROM eclipse-temurin:21-jre

WORKDIR /app

# The installDist layout: bin/server (start script) + lib/*.jar
COPY server/build/install/server/ ./

# The server binds 0.0.0.0:8080 (see Application.kt), so it is reachable from outside.
EXPOSE 8080

ENTRYPOINT ["/app/bin/server"]
