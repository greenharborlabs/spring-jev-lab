import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    id("org.springframework.boot") version "4.0.8"
}

group = "com.greenharborlabs"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter-web")

    testImplementation(platform(SpringBootPlugin.BOM_COORDINATES))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

// Live checks call the real Jev API. They are not part of `check` and need TYPESAFE_API_KEY.
val liveTest: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

configurations[liveTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[liveTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

tasks.register<Test>("liveTest") {
    description = "Runs the labelled corpus against the real Jev API."
    group = "verification"
    testClassesDirs = liveTest.output.classesDirs
    classpath = liveTest.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
    outputs.upToDateWhen { false }
    testLogging {
        showStandardStreams = true
    }
    doFirst {
        if (System.getenv("TYPESAFE_API_KEY").isNullOrBlank()) {
            throw GradleException("BLOCKED: TYPESAFE_API_KEY is not set, so the live Jev check cannot run.")
        }
    }
}
