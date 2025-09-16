FROM openjdk:11-jdk AS build
COPY . /jvm-libp2p
WORKDIR /jvm-libp2p
RUN ./gradlew build -x test --no-daemon

FROM openjdk:11-jdk
WORKDIR /jvm-libp2p
COPY --from=build /jvm-libp2p/interop-test-client/build/distributions/interop-test-client*.tar .
RUN tar -xf interop-test-client*.tar && rm interop-test-client*.tar

ENTRYPOINT ["/jvm-libp2p/interop-test-client-develop/bin/interop-test-client"]
EXPOSE 4001