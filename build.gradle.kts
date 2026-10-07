import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.3.21"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        // Compiled against; at runtime the Terminal integration is optional (plugin.xml).
        bundledPlugin("org.jetbrains.plugins.terminal")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        // Match the Kotlin stdlib bundled with the oldest supported IDE.
        apiVersion.set(KotlinVersion.KOTLIN_2_2)
        languageVersion.set(KotlinVersion.KOTLIN_2_2)
        // Platform interfaces use real Java default methods; don't emit
        // compatibility bridges (the verifier flags them as overrides).
        jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
    }
}

intellijPlatform {
    // Pure Kotlin, no GUI forms or Java @NotNull instrumentation needed.
    instrumentCode = false
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            // -PverifyIdes=2025.3.6,2026.2.3 checks specific IntelliJ IDEA versions;
            // by default the IDEs JetBrains recommends for our since-build.
            val only = providers.gradleProperty("verifyIdes").orNull
            if (only.isNullOrBlank()) {
                recommended()
            } else {
                only.split(',').map(String::trim).filter(String::isNotEmpty).forEach {
                    create(IntelliJPlatformType.IntellijIdea, it)
                }
            }
        }
    }
}

// ---- Native library -------------------------------------------------------

val nativeRoot = layout.projectDirectory.dir("native")
val nativeDistDir = layout.projectDirectory.dir(providers.gradleProperty("nativeDistDir").get())

/** Plugin platform directory name for the machine running the build. */
fun hostPlatform(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = when (System.getProperty("os.arch").lowercase()) {
        "amd64", "x86_64" -> "x64"
        "aarch64", "arm64" -> "aarch64"
        else -> System.getProperty("os.arch")
    }
    return when {
        os.contains("win") -> "windows-$arch"
        os.contains("mac") -> "darwin-$arch"
        else -> "linux-$arch"
    }
}

fun hostLibraryName(): String = when {
    hostPlatform().startsWith("windows") -> "ghostty_jb.dll"
    hostPlatform().startsWith("darwin") -> "libghostty_jb.dylib"
    else -> "libghostty_jb.so"
}

// Builds the native bridge (Rust crate in native/, which builds libghostty-vt
// with Zig) for the host into native/dist/<platform>/.
val buildNativeHost = tasks.register<Exec>("buildNativeHost") {
    group = "build"
    description = "Builds the ghostty-jb native library for the host platform (needs cargo and zig 0.16)."
    workingDir = nativeRoot.asFile
    commandLine("cargo", "build", "--release", "--locked")
    inputs.dir(nativeRoot.dir("src"))
    inputs.files(nativeRoot.file("Cargo.toml"), nativeRoot.file("Cargo.lock"), nativeRoot.file("build.rs"), nativeRoot.file("build.zig"))
    val built = nativeRoot.file("target/release/${hostLibraryName()}").asFile
    val dest = nativeDistDir.dir(hostPlatform()).asFile
    outputs.file(built)
    doLast {
        dest.mkdirs()
        built.copyTo(dest.resolve(built.name), overwrite = true)
    }
}

tasks {
    // Every sandbox (runIde, tests, buildPlugin) ships each platform present in
    // the native dist dir as <plugin>/native/<os>-<arch>/<lib>.
    withType<PrepareSandboxTask>().configureEach {
        from(nativeDistDir) {
            into(intellijPlatform.projectName.map { "$it/native" })
        }
        // libghostty is statically linked into the native library: ship its license.
        from(nativeRoot.file("vendor/ghostty/LICENSE")) {
            rename { "LICENSE-ghostty" }
            into(intellijPlatform.projectName.map { "$it/native" })
        }
    }

    runIde {
        // Sandbox only: skip first-run dialogs so the IDE opens straight into a project.
        jvmArgs(
            "-Didea.trust.all.projects=true",
            "-Dide.show.tips.on.startup.default.value=false",
            "-Djb.consents.confirmation.enabled=false",
            "-Djb.privacy.policy.text=<!--999.999-->",
            "-Didea.initially.ask.config=never",
            "-Dide.newUsersOnboarding=false",
        )
    }

    test {
        // Unit tests talk to the real native library.
        systemProperty(
            "ghostty.jb.library",
            nativeDistDir.dir(hostPlatform()).file(hostLibraryName()).asFile.absolutePath,
        )
    }
}
