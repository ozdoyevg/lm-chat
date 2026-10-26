import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij")
}

group = "dev.lmchat"
version = "0.9.0"

repositories { mavenCentral() }

dependencies {
    // The IDE already ships Kotlin and Gson, so only the core classes are bundled.
    implementation(project(":core")) {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains")
        exclude(group = "com.google.code.gson")
    }
}

intellij {
    version.set("2022.1.3")
    type.set("IC")
    plugins.set(emptyList())
}

tasks {
    withType<JavaCompile> { sourceCompatibility = "11"; targetCompatibility = "11" }
    withType<KotlinCompile> { kotlinOptions.jvmTarget = "11" }
    patchPluginXml { sinceBuild.set("221"); untilBuild.set("221.*") }
    buildSearchableOptions { enabled = false }
    runIde { jvmArgs("-Dlmchat.demo=true") }
}