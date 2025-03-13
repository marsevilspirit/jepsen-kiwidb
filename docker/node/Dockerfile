FROM ubuntu:22.04 AS builder

RUN apt-get update && apt-get install -y build-essential make pkg-config wget unzip

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

ADD entrypoint.sh /entrypoint.sh
RUN chmod 0755 /entrypoint.sh

EXPOSE 22

ENTRYPOINT ["/entrypoint.sh"]
