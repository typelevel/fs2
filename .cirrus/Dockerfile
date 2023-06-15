FROM sbtscala/scala-sbt:eclipse-temurin-jammy-17.0.5_8_1.9.0_3.3.0

RUN apt-get update && apt-get install -y build-essential clang libssl-dev
RUN git clone https://github.com/aws/s2n-tls.git && cd s2n-tls && cmake -S . -B build && cmake --build build && cmake --install build

ENV S2N_DONT_MLOCK=1
