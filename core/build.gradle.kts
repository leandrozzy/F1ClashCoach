import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    api("com.squareup.okhttp3:okhttp:4.12.0")
    // SDK oficial da Anthropic (Claude).
    api("com.anthropic:anthropic-java:2.75.0")
    // Alinha o kotlin-reflect (usado pelo SDK) com a versão do Kotlin do projeto.
    api("org.jetbrains.kotlin:kotlin-reflect:2.2.20")

    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
}

tasks.test {
    testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
