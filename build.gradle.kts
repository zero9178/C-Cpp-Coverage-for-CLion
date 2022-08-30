import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")
    id("org.jetbrains.intellij") version "1.8.1"
    id("org.jetbrains.kotlin.jvm") version "1.7.10"
}

group = "net.zero9178"
version = "2021.3.0-Eap"

repositories {
    jcenter()
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation("com.beust:klaxon:5.2")
    implementation("com.beust:klaxon-jackson:1.0.1")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.github.h0tk3y.betterParse:better-parse-jvm:0.4.2")
}

// See https://github.com/JetBrains/gradle-intellij-plugin/
intellij {
    version.set("2022.2.1")
    plugins.set(listOf("com.intellij.clion", "com.intellij.cidr.base", "com.intellij.cidr.lang", "clion-ctest"))
    type.set("CL")
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    patchPluginXml {
        version.set(project.version.toString())
    }

    runIde {
        jvmArgs("-Xmx4G")
    }
}

