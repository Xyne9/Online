# syntax=docker/dockerfile:1
#
# go-judge 判题沙箱镜像（固化判题所需的语言工具链）
#
# 为什么需要这层 Dockerfile：
#   官方 `criyle/go-judge:latest` 内不含任何编译器/解释器（只有 perl）。
#   若在容器运行期用 `docker exec -u 0 ... apt-get install` 装 gcc/python3/openjdk，
#   这些文件只落在**容器可写层**，容器一经重建（`docker compose down` 后再 up）就全部丢失，
#   表现为「判题服务活着，但每次编译都失败」——不报错、最难排查。
#   把工具链固化进镜像层后，判题环境才可复现。
#
# 对应语言：
#   gcc / g++  -> C / C++
#   openjdk-21 -> Java（提供 javac 与 java）
#   python3    -> Python 3
#
# 构建：
#   docker build -t oj-go-judge:latest docker/judge
# 国内网络（apt 走镜像站）：
#   docker build --build-arg APT_MIRROR=https://mirrors.aliyun.com -t oj-go-judge:latest docker/judge
#
# 沙箱隔离参数（privileged / cgroupns / shm）由 docker-compose.yml 负责，本文不涉及。

FROM criyle/go-judge:latest

# Debian apt 源默认走国内镜像（阿里云），避免 deb.debian.org 在国内构建时超时。
# 海外网络或想用官方源时：docker build --build-arg APT_MIRROR= -t oj-go-judge:latest docker/judge
ARG APT_MIRROR="https://mirrors.aliyun.com"

RUN set -eux; \
    if [ -n "$APT_MIRROR" ]; then \
        sed -i \
            -e "s|http://deb.debian.org/debian-security|${APT_MIRROR}/debian-security|g" \
            -e "s|http://deb.debian.org/debian|${APT_MIRROR}/debian|g" \
            /etc/apt/sources.list.d/*.sources; \
    fi; \
    apt-get update; \
    apt-get install -y --no-install-recommends \
        gcc \
        g++ \
        make \
        python3 \
        openjdk-21-jdk-headless; \
    rm -rf /var/lib/apt/lists/*

# 构建期自检：任一工具链缺失就让构建失败，
# 杜绝「镜像构建成功、但容器里其实没有编译器」这类静默失败。
RUN set -eux; \
    gcc --version; \
    g++ --version; \
    make --version; \
    python3 --version; \
    javac -version; \
    java -version

# 健康检查：基础镜像自带 perl，用它做 TCP 探活（镜像内没有 curl/wget）。
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=5 \
    CMD ["perl", "-MIO::Socket::INET", "-e", "exit(IO::Socket::INET->new(PeerHost=>\"127.0.0.1\",PeerPort=>5050,Proto=>\"tcp\",Timeout=>3) ? 0 : 1)"]

# 显式固定入口，避免上游基础镜像调整 CMD/ENTRYPOINT 后判题服务不再自启。
ENTRYPOINT ["/opt/go-judge"]
