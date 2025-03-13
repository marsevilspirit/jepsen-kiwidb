FROM ubuntu:22.04 AS builder

RUN apt-get update && apt-get install -y build-essential make pkg-config wget unzip

RUN wget https://github.com/redis/redis/archive/refs/tags/7.4.1.zip && \
    unzip 7.4.1.zip && rm -rf 7.4.1.zip && cd redis-7.4.1 && make

FROM ubuntu:22.04

RUN apt-get update && \
    apt-get install -y \
        openssh-server \
        pwgen && \
    mkdir -p /var/run/sshd && \
    sed -i "s/UsePrivilegeSeparation.*/UsePrivilegeSeparation no/g" /etc/ssh/sshd_config && \
    sed -i "s/PermitRootLogin prohibit-password/PermitRootLogin yes/g" /etc/ssh/sshd_config && \
    echo "PubkeyAcceptedKeyTypes=+ssh-rsa" >> /etc/ssh/sshd_config

RUN apt-get update && \
    apt-get install -y \
        apt-transport-https \
        software-properties-common \
        build-essential \
        bzip2 \
        curl \
        faketime \
        iproute2 \
        iptables \
        iputils-ping \
        libzip4 \
        logrotate \
        man \
        man-db \
        net-tools \
        ntpdate \
        psmisc \
        python3 \
        rsyslog \
        sudo \
        tar \
        unzip \
        vim \
        wget \
        tcpdump \
        git \
        cmake \
        automake \
        autoconf \
        libtool \
        libssl-dev \
        zlib1g-dev && \
    apt-get remove -y --purge --auto-remove systemd

# aarch64
ARG ARCH=x86_64
RUN wget https://cmake.org/files/v3.26/cmake-3.26.6-linux-$ARCH.tar.gz -O cmake-3.26.6-linux-$ARCH.tar.gz && \
    tar xzvf cmake-3.26.6-linux-x86_64.tar.gz && \
    cd cmake-3.26.6-linux-x86_64 && \
    mv bin/* /usr/bin/ && \
    mv share/cmake-3.26 /usr/share/ && \
    cd .. && \
    rm -rf cmake-3.26.6-linux-x86_64.tar.gz && \
    rm -rf cmake-3.26.6-linux-x86_64

COPY --from=builder /redis-7.4.1/src/redis-cli /bin/redis-cli

ADD entrypoint.sh /entrypoint.sh
RUN chmod 0755 /entrypoint.sh

EXPOSE 22

ENTRYPOINT ["/entrypoint.sh"]
