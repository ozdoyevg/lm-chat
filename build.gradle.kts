plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.6.21"
    id("org.jetbrains.intellij") version "1.13.3"
}

group = "dev.lmchat"
version = "0.1.0"

repositories { mavenCentral() }

intellij {
    // Build against IntelliJ Community 2022.1.3: the plugin only needs the platform,
    // so it also runs in PhpStorm 2022.1.3 and avoids the PhpStorm download.
    version.set("2022.1.3")
    type.set("IC")
    plugins.set(emptyList())
}

tasks {
    withType<JavaCompile> { sourceCompatibility = "11"; targetCompatibility = "11" }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> { kotlinOptions.jvmTarget = "11" }
    patchPluginXml { sinceBuild.set("221"); untilBuild.set("221.*") }
    buildSearchableOptions { enabled = false }
    runIde {
        jvmArgs("-Dlmchat.demo=true")
    }
}