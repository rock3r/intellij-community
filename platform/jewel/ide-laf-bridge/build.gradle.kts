import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import java.net.URI

plugins {
    jewel
    `jewel-check-public-api`
    alias(libs.plugins.composeDesktop)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ideaPluginModule)
    alias(libs.plugins.kotlinx.serialization)
}

// Because we need to define IJP dependencies, the dependencyResolutionManagement
// from settings.gradle.kts is overridden and we have to redeclare everything here.
repositories {
    google()
    mavenCentral()

    intellijPlatform {
        ivy {
            name = "PKGS IJ Snapshots"
            url = URI("https://packages.jetbrains.team/files/p/kpm/public/idea/snapshots/")
            patternLayout {
                artifact("[module]-[revision](-[classifier]).[ext]")
                artifact("[module]-[revision](.[classifier]).[ext]")
            }
            metadataSources { artifact() }
        }

        defaultRepositories()
    }
}

dependencies {
    api(projects.ui) { exclude(group = "org.jetbrains.kotlinx") }
    intellijPlatform {
        intellijIdea(libs.versions.idea)
        testFramework(TestFrameworkType.Platform)

        bundledPlugin("org.jetbrains.plugins.textmate")
    }

    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(compose.desktop.uiTestJUnit4) { excludeCoroutines() }
    testImplementation(libs.mockk) { excludeCoroutines() }
    testImplementation(compose.desktop.currentOs) {
        exclude(group = "org.jetbrains.compose.material")
        excludeCoroutines()
    }
}

fun ModuleDependency.excludeCoroutines() {
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
}

sourceSets {
    test { kotlin { srcDirs("ide-laf-bridge-tests/src/test/kotlin") } }

    // The platform-fixture integration tests under src/test/kotlin boot a real test application, which
    // needs the in-monorepo platform; they run via Bazel (ideLafBridge-tests_test). The standalone build
    // compiles them against the SDK but must not execute them.
    tasks.test { exclude("**/JewelBridgeActionIntegrationTest*") }

    // Compile-only stubs for platform API introduced with IJPL-212347 but not yet present in the
    // released IJP artifacts this build resolves. Never packaged; the platform's own classes win at
    // runtime. Delete together with ijp-api-stubs/ once libs.versions.toml's `idea` version ships
    // com.intellij.ide.KeyboardAwareFocusOwnerProvider.
    val ijpApiStubs by creating { kotlin.srcDir("ijp-api-stubs/src/main/kotlin") }
    main { compileClasspath += ijpApiStubs.output }
    test { compileClasspath += ijpApiStubs.output }
}

dependencies {
    // Satisfies the project-wide Compose compiler plugin's runtime check for the stubs-only compilation.
    "ijpApiStubsCompileOnly"(compose.runtime)
}
