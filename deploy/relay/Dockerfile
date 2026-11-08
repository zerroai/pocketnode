# Build the relay on your machine first (the Android modules need the Android SDK, so the image does not compile anything):
#   ./gradlew :relay:installDist
# then: docker build -f deploy/relay/Dockerfile -t pocketnode-relay relay/build/install/relay
FROM eclipse-temurin:17-jre
RUN useradd --system --create-home --uid 10001 relay && mkdir /data && chown relay /data
COPY --chown=relay . /opt/relay
USER relay
ENV PORT=8088 RELAY_DB=/data/relay.db RELAY_TRUST_PROXY=1
EXPOSE 8088
VOLUME /data
HEALTHCHECK --interval=30s --timeout=3s CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8088 && printf 'GET /healthz HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q ' 200 ' <&3"]
ENTRYPOINT ["/opt/relay/bin/relay"]
