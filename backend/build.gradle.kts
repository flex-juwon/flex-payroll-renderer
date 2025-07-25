plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("com.google.cloud.tools.jib")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("com.microsoft.playwright:playwright:1.54.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    compilerOptions {
        jvmToolchain(21)
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(rootProject.tasks.named("copyFrontend"))
}

tasks.named<Delete>("clean") {
    delete("${projectDir}/src/main/resources/public")
}

jib {
    from {
        image = "eclipse-temurin:21-jre"
    }

    to {
        image = "flex-payroll-renderer:latest"
    }

    container {
        mainClass = "team.flex.payroll.PayrollRendererApplicationKt"
        ports = listOf("8080")
        creationTime.set("USE_CURRENT_TIMESTAMP")
    }
}
