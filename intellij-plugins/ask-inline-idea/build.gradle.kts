plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.24"
    // IntelliJ Platform Gradle Plugin 2.x.
    id("org.jetbrains.intellij.platform") version "2.6.0"
}

group = "com.example"
version = "0.0.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Target IntelliJ IDEA Community; bump as needed.
        intellijIdeaCommunity("2024.1")
        instrumentationTools()
    }
    // Gson is bundled in the platform, but declare for compile-time visibility.
    // (No runtime jar shipped — platform provides it.)

    // JetBrains' GFM markdown engine (org.intellij.markdown.*). NOT bundled in
    // the platform — the Markdown plugin ships org.intellij.plugins.markdown.*
    // (the IDE integration), not the parser. Bundled into our plugin jar for
    // full GitHub-Flavored-Markdown parity.
    implementation("org.jetbrains:markdown:0.7.3") {
        // The platform ships its own Kotlin stdlib; bundling a second copy risks
        // classloader/version conflicts. Keep only the markdown engine.
        exclude(group = "org.jetbrains.kotlin")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "241"
            untilBuild = "261.*"
        }
    }
}

kotlin {
    // Compile with JDK 21 (the only JDK installed) but EMIT Java 17 bytecode —
    // the sandbox/target IDE runs on JBR 17, which rejects class file v65 (Java
    // 21). Without this, the plugin fails to load with UnsupportedClassVersionError.
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
