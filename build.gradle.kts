plugins {
    kotlin("jvm") version "2.4.10" apply false
    kotlin("plugin.spring") version "2.4.10" apply false
    id("org.springframework.boot") version "4.1.1" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

allprojects {
    group = "com.josue.transactions"
    version = "0.1.0-SNAPSHOT"
    extra["kotlin.version"] = "2.4.10"

    repositories {
        mavenCentral()
    }
}
