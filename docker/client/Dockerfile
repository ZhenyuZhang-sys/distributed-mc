# 第 9 步：无头的真 Minecraft 客户端（HeadlessMC，版本和校验和写死），只给测试用（compose 的 profile client）。
# 用户 11-09 同意在测试容器里运行 HeadlessMC；它从 Mojang、Fabric 的官方地址下载客户端，放在容器卷（/cache），不进仓库。
# Java：eclipse-temurin 21（jdk-21.0.13+9），按 digest 固定。
FROM eclipse-temurin@sha256:ebfbb18d11ff163ee83b21258acf12248af48d22a0fdfdc68fa1a1516c05976a
ARG HMC_VERSION=2.10.0
ARG HMC_SHA256=52bd5006f478377b3893011d458562977d38c65ead6d2b31089beb4d614f13cd
RUN apt-get update && apt-get install -y --no-install-recommends curl procps && rm -rf /var/lib/apt/lists/* \
 && curl -fsSL -o /opt/headlessmc.jar \
    "https://github.com/headlesshq/headlessmc/releases/download/${HMC_VERSION}/headlessmc-launcher-${HMC_VERSION}.jar" \
 && echo "${HMC_SHA256}  /opt/headlessmc.jar" | sha256sum -c -
COPY run.sh /opt/run.sh
ENTRYPOINT ["bash", "/opt/run.sh"]
