import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins { id("org.jetbrains.kotlin.jvm") }

repositories { mavenCentral() }

dependencies {
    api(kotlin("stdlib"))
    api("com.google.code.gson:gson:2.8.9")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

tasks.withType<KotlinCompile> { kotlinOptions.jvmTarget = "11" }