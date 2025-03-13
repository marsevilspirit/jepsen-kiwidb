FROM ubuntu:22.04 AS builder

RUN apt-get update && apt-get install -y build-essential make pkg-config wget unzip

FROM ubuntu:22.04

RUN apt-get update && \
    apt-get install -y \
        openjdk-11-jdk \
        libjna-java \
        git \
        gnuplot \
        wget \
        vim \
        graphviz \
        build-essential \
        autoconf \
        zlib1g-dev

ENV LEIN_ROOT true
RUN wget https://raw.githubusercontent.com/technomancy/leiningen/stable/bin/lein && \
    mv lein /usr/bin && \
    chmod +x /usr/bin/lein && \
    lein self-install

ADD entrypoint.sh /entrypoint.sh
RUN chmod 0755 /entrypoint.sh

# for lein run serve the web
EXPOSE 8080

ENTRYPOINT ["/entrypoint.sh"]
