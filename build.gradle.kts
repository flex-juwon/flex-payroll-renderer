plugins {
    idea
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.spring") version "2.1.20" apply false
    id("org.springframework.boot") version "3.5.3" apply false
    id("io.spring.dependency-management") version "1.1.6" apply false
    id("com.google.cloud.tools.jib") version "3.4.5" apply false
    id("com.github.node-gradle.node") version "7.0.2"
}

allprojects {
    repositories {
        mavenCentral()
    }
}

// frontend build

node {
    version.set("22.15.1")
    download.set(true)
    workDir.set(file("${project.buildDir}/node"))
    nodeProjectDir.set(file("${project.projectDir}/frontend") )
}

val frontendDir = "${project.projectDir}/frontend"
val publishDir = "${project.projectDir}/backend/src/main/resources/public"

val buildFrontend = tasks.register<com.github.gradle.node.npm.task.NpmTask>("buildFrontend") {
    dependsOn(tasks.npmInstall)
    workingDir.set(file(frontendDir))
    args.set(listOf("run", "build"))
}

val copyFrontend = tasks.register("copyFrontend") {
    dependsOn(buildFrontend)

    doLast {
        val targetDir = file(publishDir)
        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }
        targetDir.mkdirs()

        copy {
            from(file("${frontendDir}/build"))
            into(targetDir)
        }
    }
}

val cleanFrontend = tasks.register<com.github.gradle.node.npm.task.NpmTask>("cleanFrontend") {
    workingDir.set(file(frontendDir))
    args.set(listOf("run", "clean"))
}

tasks.named("clean") {
    dependsOn(cleanFrontend)
}

// frontend development

val runFrontend = tasks.register<com.github.gradle.node.npm.task.NpmTask>("runFrontend") {
    dependsOn(tasks.npmInstall)
    workingDir.set(file(frontendDir))
    args.set(listOf("run", "start"))
}
