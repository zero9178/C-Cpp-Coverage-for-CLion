import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("java")
    id("org.jetbrains.intellij") version "1.1.6"
    id("org.jetbrains.kotlin.jvm") version "1.5.0"
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
    implementation("org.jetbrains.kotlin:kotlin-reflect:1.5.0")
    implementation("com.github.h0tk3y.betterParse:better-parse-jvm:0.4.2")
}

// See https://github.com/JetBrains/gradle-intellij-plugin/
intellij {
    version.set("213.3714-EAP-CANDIDATE-SNAPSHOT")
    plugins.set(listOf("com.intellij.clion", "com.intellij.cidr.base", "com.intellij.cidr.lang", "clion-ctest"))
    type.set("CL")
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "11"
        targetCompatibility = "11"
    }
    withType<KotlinCompile> {
        kotlinOptions.jvmTarget = "11"
    }

    patchPluginXml {
        version.set(project.version.toString())
    }

    runIde {
        jvmArgs("-Xmx4G")
    }
}

