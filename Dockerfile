FROM ubuntu:24.04

# 阿里云源
RUN sed -i 's@//.*archive.ubuntu.com@//mirrors.aliyun.com@g' /etc/apt/sources.list.d/ubuntu.sources && \
    sed -i 's@//security.ubuntu.com@//mirrors.aliyun.com@g' /etc/apt/sources.list.d/ubuntu.sources && \
    sed -i 's@//ports.ubuntu.com@//mirrors.aliyun.com@g' /etc/apt/sources.list.d/ubuntu.sources

ENV DEBIAN_FRONTEND=noninteractive
ENV LANG=zh_CN.UTF-8
ENV LC_ALL=zh_CN.UTF-8

# 安装依赖（JDK、LibreOffice、中文字体）
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        openjdk-8-jre \
        fontconfig \
        libreoffice-nogui \
        ttf-wqy-zenhei \
        ttf-wqy-microhei && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# 刷新字体缓存
RUN fc-cache -fv

WORKDIR /app

# 从 Maven 编译结果复制 war 包
COPY target/FileServer.war /app/FileServer.war

# 配置 LibreOffice 路径
ENV office.home=/usr/lib/libreoffice
EXPOSE 8012

CMD ["java", "-jar", "FileServer.war"]
