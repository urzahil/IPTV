plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    // XmlPullParser API only. On Android the platform implementation is used at runtime.
    compileOnly(libs.kxml2)

    testImplementation(libs.kxml2)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}

tasks.withType<Test> {
    useJUnit()
    testLogging { events("failed") ; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
