## flex-payroll-renderer

### Run frontend locally

```shell
./gradlew runFrontend
# localhost:3000
```

### Run backend locally

```shell
./gradlew bootRun
# localhost:8080
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
