# Multi-arch image (amd64 / arm64 / arm/v7) using Eclipse Temurin JRE 11
FROM eclipse-temurin:11-jre-alpine

RUN addgroup -S habridge && adduser -S habridge -G habridge

WORKDIR /ha-bridge

# Copy the shaded JAR produced by `mvn package`
COPY target/ha-bridge-*.jar ha-bridge.jar

RUN mkdir -p data && chown -R habridge:habridge /ha-bridge

USER habridge

# Default HTTP port (adjust via -Dserver.port if needed)
EXPOSE 80 8080

ENTRYPOINT ["java", "-jar", "ha-bridge.jar"]
CMD ["-Dconfig.file=/ha-bridge/data/habridge.config"]
