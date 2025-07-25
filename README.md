## flex-payroll-renderer

PAY-12961 pdf 렌더러 서버의 Kopring 포팅 가능성을 확인한다

핵심 작동 구조

![](docs/sequence.JPEG)

- 템플릿은 frontend 폴더에 작성한다
- backend 빌드 시점에 frontend 빌드 아티팩트는 backend 정적 리소스 서빙 위치로 복사한다
- 애플리케이션이 구동되면
  - HelloController가 pdf 렌더링 api 요청을 받는다
  - PdfRender가 PlayWright를 구동하고, public 폴더에 위치한 index.html을 브라우저에서 렌더링한다
  - PlayWright는 렌더링된 html 페이지를 pdf 바이트 배열로 HelloController에 반환한다
  - HelloController가 pdf 파일을 응답한다

### Run frontend locally

```shell
./gradlew runFrontend
# localhost:3000
```

### Run backend & frontend locally

```shell
./gradlew bootRun
# localhost:8080
```

test

```shell
curl -H 'Content-type: application/json' -X POST http://localhost:8080/api/hello -d '{"name": "John"}' > ~/Desktop/hello.pdf
open ~/Desktop/hello.pdf
```

### Build docker

```shell
./gradlew jibDockerBuild

$ docker image ls
# REPOSITORY                TAG      IMAGE ID       CREATED         SIZE
# flex-payroll-renderer     latest   414182469c49   9 seconds ago   124MB

$ docker run -it --rm -p 8080:8080 flex-payroll-renderer:latest
# 2025-07-25T05:54:55.132Z  INFO 1 --- [           main] t.f.p.PayrollRendererApplicationKt       : Started PayrollRendererApplicationKt in 2.927 seconds (process running for 3.798)
```

### TODO

- [ ] Dockerfile 작성
- [ ] 처리량 측정

```shell
# jib에 적용한 base linux image에 PlayWright를 구동하기 위한 라이브러리 필요
$ docker run -it --rm -p 8080:8080 flex-payroll-renderer:latest
# ╔═════════════════════════════════════════════════════════════════════════════════════════════════╗
# ║ Host system is missing dependencies to run browsers.                                            ║
# ║ Please install them with the following command:                                                 ║
# ║                                                                                                 ║
# ║     mvn exec:java -e -D exec.mainClass=com.microsoft.playwright.CLI -D exec.args="install-deps" ║
# ║                                                                                                 ║
# ║ Alternatively, use apt:                                                                         ║
# ║     apt-get install libglib2.0-0t64\                                                            ║
# ║         libnspr4\                                                                               ║
# ║         libnss3\                                                                                ║
# ║         libdbus-1-3\                                                                            ║
# ║         libatk1.0-0t64\                                                                         ║
# ║         libatk-bridge2.0-0t64\                                                                  ║
# ║         libatspi2.0-0t64\                                                                       ║
# ║         libx11-6\                                                                               ║
# ║         libxcomposite1\                                                                         ║
# ║         libxdamage1\                                                                            ║
# ║         libxext6\                                                                               ║
# ║         libxfixes3\                                                                             ║
# ║         libxrandr2\                                                                             ║
# ║         libgbm1\                                                                                ║
# ║         libxcb1\                                                                                ║
# ║         libxkbcommon0\                                                                          ║
# ║         libasound2t64                                                                           ║
# ║                                                                                                 ║
# ║ <3 Playwright Team                                                                              ║
# ╚═════════════════════════════════════════════════════════════════════════════════════════════════╝
```
