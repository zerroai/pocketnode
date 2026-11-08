# Build:  docker build -f relay/Dockerfile -t pocketnode-relay .
# Run:    docker run -p 8088:8088 -v relaydata:/data -e RELAY_PUBLIC_URL=https://relay.example.com -e RELAY_STORAGE_KEY=<64 hex> -e RELAY_DB=/data/relay.db pocketnode-relay
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon :relay:installDist

FROM eclipse-temurin:17-jre
RUN useradd -r -u 10001 relay && mkdir /data && chown relay /data
COPY --from=build /src/relay/build/install/relay /opt/relay
USER relay
ENV PORT=8088 RELAY_DB=/data/relay.db
EXPOSE 8088
CMD ["/opt/relay/bin/relay"]
