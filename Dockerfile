# buildx builder 생성 (최초 1회만)
#   docker buildx create --name multiarch-builder --use
#   docker buildx inspect --bootstrap
#
# 멀티아키텍처 이미지 빌드
#   docker buildx build --platform linux/amd64,linux/arm64 -t playwright:latest .

FROM eclipse-temurin:21-jre

RUN apt-get update && apt-get install -y --no-install-recommends \
    maven \
    curl \
    unzip \
    gnupg \
    && rm -rf /var/lib/apt/lists/*

# Maven 기반으로 Playwright install-deps 실행
RUN mkdir -p /tmp/maven && \
    cat <<'EOT' > /tmp/maven/pom.xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>dummy</groupId>
    <artifactId>playwright-install</artifactId>
    <version>1.0.0</version>

    <dependencies>
        <dependency>
            <groupId>com.microsoft.playwright</groupId>
            <artifactId>playwright</artifactId>
            <version>1.54.0</version>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>exec-maven-plugin</artifactId>
                <version>3.1.0</version>
            </plugin>
        </plugins>
    </build>
</project>
EOT

WORKDIR /tmp/maven

RUN mvn exec:java -Dexec.mainClass=com.microsoft.playwright.CLI -Dexec.args="install-deps"
