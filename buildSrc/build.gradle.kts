import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
    kotlin("plugin.serialization") version "2.2.21"
}

repositories {
    gradlePluginPortal()
}

tasks.compileKotlin.configure {
    compilerOptions.jvmTarget = JvmTarget.JVM_24
}

tasks.compileJava.configure {
    options.release = 24
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.ow2.asm:asm:9.7.1")
    testImplementation(kotlin("test-junit"))
}
