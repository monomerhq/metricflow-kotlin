plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

// Versions are duplicated here (against gradle/libs.versions.toml) because
// buildSrc cannot read the root version catalog at its own configuration time.
// Keep these two lists in sync.
dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.20")
    implementation("org.jetbrains.kotlin:kotlin-serialization:2.3.20")
    implementation("com.google.protobuf:protobuf-gradle-plugin:0.10.0")
}
