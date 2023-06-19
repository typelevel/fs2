FROM eclipse-temurin:17

RUN apt-get update && apt-get install -y build-essential clang cmake libssl-dev
RUN wget -q https://github.com/sbt/sbt/releases/download/v1.9.0/sbt-1.9.0.tgz && tar xvfz sbt-1.9.0.tgz

RUN git clone https://github.com/aws/s2n-tls.git && cd s2n-tls && cmake -S . -B build && cmake --build build && cmake --install build

ENV PATH="$PATH:/sbt/bin"
ENV S2N_DONT_MLOCK=1
