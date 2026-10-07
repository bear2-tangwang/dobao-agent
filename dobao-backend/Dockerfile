# syntax=docker/dockerfile:1

# ============================================================================
# dobao-backend 生产镜像
#
# 构建（必须在【仓库根目录】执行 —— 构建上下文要同时访问 dobao-backend/ 与 deploy/）：
#     docker build -f dobao-backend/Dockerfile -t dobao-backend:1.0.0 .
#
# 多阶段：Maven 阶段产出可执行 jar，运行阶段只保留 JRE + Python 运行时。
# ============================================================================

# ------------------------------- 构建阶段 -----------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build

# 阿里云 Maven 镜像：境内服务器直连 Maven Central 很容易超时
COPY deploy/maven-settings.xml /root/.m2/settings.xml

# 先只拷 pom 并预热依赖 —— pom 不变时这一层命中缓存，重建不必重新下载依赖
COPY dobao-backend/pom.xml ./
RUN mvn -B -q dependency:go-offline -DskipTests || true

COPY dobao-backend/src ./src
RUN mvn -B -DskipTests clean package

# ------------------------------- 运行阶段 -----------------------------------
FROM eclipse-temurin:21-jre-jammy

ENV TZ=Asia/Shanghai \
    LANG=C.UTF-8 \
    SPRING_PROFILES_ACTIVE=prod

# 运行期系统依赖：
#
#   python3 + python-pptx + python3-requests
#     —— PPT 渲染脚本 src/main/resources/python/render_ppt.py 的依赖
#        （脚本里 import pptx / requests）
#
#   ⚠️ PptPythonRenderServiceImpl 是用字符串 "python" 拉起子进程的：
#        List.of("python", pythonScriptPath, ...) → new ProcessBuilder(command)
#      ProcessBuilder 只按 PATH 查找，而 Ubuntu 只提供 python3，
#      所以必须建 /usr/local/bin/python 软链，否则 PPT 功能必然报
#      "Cannot run program \"python\": error=2, No such file or directory"
#
#   python3-pptx 优先走 apt（顺带把 lxml / Pillow 一起装好，也避开 pip 与 PEP 668 的纠缠）；
#   极少数源里没有该包的情况下回退到 pip。
RUN apt-get update && apt-get install -y --no-install-recommends \
        python3 \
        python3-pip \
        python3-requests \
        tzdata \
        curl \
    && (apt-get install -y --no-install-recommends python3-pptx \
        || pip3 install --no-cache-dir python-pptx) \
    && ln -sf /usr/bin/python3 /usr/local/bin/python \
    && rm -rf /var/lib/apt/lists/*

# 自检：依赖不齐就让【构建】失败，而不是等线上点了"生成PPT"才暴露
RUN python -c "import pptx, requests; print('python deps OK, python-pptx', pptx.__version__)"

WORKDIR /app

# PptPythonRenderServiceImpl.getOutputDir() 用
#   System.getProperty("user.dir") + "/output/ppt"
# 当 PPT 临时目录（生成后上传 MinIO 再删除），所以 WORKDIR 必须是可写目录，
# 并且提前把目录建好，避免首次生成时因权限/不存在而失败。
RUN mkdir -p /app/output/ppt /app/logs

# 只拷 Spring Boot 重打包后的可执行 jar；通配符不会匹配到 *.jar.original
COPY --from=build /build/target/dobao-backend-*.jar /app/app.jar

# 2核8G 机器上的默认堆参数：
#   MaxRAMPercentage=50 —— 在容器内存上限内取一半给堆。按 compose 里 backend 限 4G 算，
#     堆约 2G，给 PostgreSQL / MinIO / 操作系统留足份额。
#     这是本项目最关键的调参：绝不要按"机器有 8G 就 -Xmx6g"来设，
#     因为 JVM、PG、MinIO 是挤在同一台机器上的。
#   ExitOnOutOfMemoryError —— 真 OOM 时立刻退出，交给 restart 策略拉起，
#     好过带着损坏状态硬撑（注意：Python 子进程的内存不在此限制内）。
ENV JAVA_OPTS="-XX:MaxRAMPercentage=50 -XX:InitialRAMPercentage=25 -XX:MaxMetaspaceSize=384m -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"

EXPOSE 8888

# sh -c 是为了让 $JAVA_OPTS 参与展开；exec 让 java 成为 PID 1 并正确接收 SIGTERM
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
