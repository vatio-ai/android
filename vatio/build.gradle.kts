plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}

// One source for the version: Vatio.VERSION, which the mirror also tags.
val sdkVersion = Regex("""const val VERSION: String = "([^"]+)"""")
    .find(file("src/main/kotlin/ai/vatio/Vatio.kt").readText())!!
    .groupValues[1]

android {
    namespace = "ai.vatio"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.github.vatio-ai"
            artifactId = "android"
            version = sdkVersion
            afterEvaluate { from(components["release"]) }
        }
    }
}
