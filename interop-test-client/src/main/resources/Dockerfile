FROM openjdk:11-jdk
WORKDIR /app
COPY ../../../../../build/docker/libs libs/
COPY ../../../../../build/docker/classes classes/
ENTRYPOINT ["java", "-cp", "/app/resources:/app/classes:/app/libs/*", "io.libp2p.interop.InteropTestAgentKt"]
EXPOSE 4041
