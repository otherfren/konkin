# ── Stage 1: build ──────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -DskipTests -B

# ── Stage 2: runtime ───────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
LABEL maintainer="konkin.io"

RUN apk add --no-cache su-exec \
 && addgroup -S konkin && adduser -S konkin -G konkin

WORKDIR /app

COPY --from=build /build/target/konkin-server-*.jar konkin-server.jar
COPY --from=build /build/src/main/resources/templates ./templates
COPY --from=build /build/src/main/resources/static    ./static
COPY docker/entrypoint.sh /entrypoint.sh

RUN mkdir -p data secrets logs && chown -R konkin:konkin /app

EXPOSE 7070

ENTRYPOINT ["/entrypoint.sh"]
