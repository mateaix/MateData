# syntax=docker/dockerfile:1
FROM node:26.10.0-bookworm-slim AS ui
WORKDIR /build/matedata-ui
COPY matedata-ui/package.json matedata-ui/package-lock.json ./
RUN npm ci
COPY matedata-ui/ ./
RUN npm test && npm run build

FROM eclipse-temurin:25-jdk AS build
WORKDIR /build
COPY . .
COPY --from=ui /build/matedata-ui/dist ./matedata-ui/dist
RUN chmod +x mvnw && ./mvnw --batch-mode --no-transfer-progress clean verify

FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app
RUN groupadd --gid 10001 matedata \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin matedata \
    && mkdir -p /app/data \
    && chown -R 10001:10001 /app
COPY --from=build --chown=10001:10001 /build/matedata-server/target/matedata-server-0.1.0-SNAPSHOT.jar /app/matedata.jar
ENV BIND_ADDRESS=0.0.0.0 \
    MATEDATA_DATA_DIR=/app/data \
    MATEDATA_DATABASE_URL="jdbc:h2:file:/app/data/matedata;DB_CLOSE_ON_EXIT=FALSE"
USER 10001:10001
VOLUME ["/app/data"]
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/matedata.jar"]
