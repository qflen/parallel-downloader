import io.gitlab.arturbosch.detekt.Detekt

plugins {
    kotlin("jvm") version "2.0.21"
    application
    jacoco
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
}

group = "com.example"
version = "1.0.0"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        allWarningsAsErrors.set(true)
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

application {
    mainClass.set("com.example.downloader.MainKt")
}

// ----- Source sets -----------------------------------------------------------
// Stress tests live in their own source set so `./gradlew test` stays fast.
// They get the production classpath, the test classpath (so they can reuse
// TestHttpServer + fakes), and their own task wired up below.
sourceSets {
    create("stressTest") {
        kotlin.srcDir("src/stressTest/kotlin")
        compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
        runtimeClasspath += output + compileClasspath
    }
}

val stressTestImplementation: Configuration by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}
val stressTestRuntimeOnly: Configuration by configurations.getting {
    extendsFrom(configurations.testRuntimeOnly.get())
}

// ----- Dependencies ----------------------------------------------------------
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // Jetty (test-only) - used by stress tests to validate the
    // chunkSize=8 MiB / parallelism=16 geometry. com.sun.net.httpserver deadlocks under
    // that load (documented in StressTest); Jetty's connector handles it without issue and
    // also gives us a server that supports HTTP/2 cleanly when we want it.
    testImplementation("org.eclipse.jetty:jetty-server:11.0.24")
}

// ----- Tasks -----------------------------------------------------------------
tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showExceptions = true
        showStackTraces = true
        showCauses = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.test {
    useJUnitPlatform {
        excludeTags("stress")
    }
    finalizedBy(tasks.jacocoTestReport)
}

val stressTest by tasks.registering(Test::class) {
    description = "Runs the stress test suite (heavy; not part of `check`)."
    group = "verification"
    testClassesDirs = sourceSets["stressTest"].output.classesDirs
    classpath = sourceSets["stressTest"].runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
    // Stress tests intentionally cap the heap to validate streaming behavior.
    maxHeapSize = "256m"
    // Ensure they always actually run (not pulled from cache) - the whole point
    // is exercising the runtime, not verifying a deterministic output.
    outputs.upToDateWhen { false }
    // The JaCoCo plugin auto-attaches its agent to every Test task. Bytecode instrumentation
    // can throttle the high-throughput streaming paths by 10x+ - exactly what the stress
    // tests are trying to measure. Coverage is collected by `tasks.test`; this task is
    // about runtime characteristics, not coverage.
    extensions.configure<JacocoTaskExtension> {
        isEnabled = false
    }
}

// ----- JaCoCo ----------------------------------------------------------------
jacoco {
    toolVersion = "0.8.12"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    classDirectories.setFrom(
        files(classDirectories.files.map {
            fileTree(it) {
                // CLI is exercised end-to-end manually; argv parsing, exit-code mapping, and the
                // CliProgressPrinter renderer aren't useful coverage targets - they're glue.
                exclude("com/example/downloader/MainKt.class")
                exclude("com/example/downloader/MainKt\$*.class")
                exclude("com/example/downloader/Cli*.class")
            }
        })
    )
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            element = "BUNDLE"
            limit {
                counter = "LINE"
                minimum = "0.90".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                minimum = "0.85".toBigDecimal()
            }
        }
    }
    classDirectories.setFrom(
        files(classDirectories.files.map {
            fileTree(it) {
                exclude("com/example/downloader/MainKt.class")
                exclude("com/example/downloader/MainKt\$*.class")
                exclude("com/example/downloader/Cli*.class")
            }
        })
    )
}

// Coverage verification gates `check`: a build fails if line coverage drops below 90% or
// branch coverage drops below 85% on production code (Main.kt is excluded - argv parsing
// line coverage isn't informative; the CLI is exercised end-to-end manually).
tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

// ----- Detekt ----------------------------------------------------------------
detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("$projectDir/config/detekt/detekt.yml"))
    source.setFrom(
        files(
            "src/main/kotlin",
            "src/test/kotlin",
            "src/stressTest/kotlin",
        )
    )
}

tasks.withType<Detekt>().configureEach {
    reports {
        html.required.set(true)
        xml.required.set(true)
        txt.required.set(false)
        sarif.required.set(false)
        md.required.set(false)
    }
    jvmTarget = "17"
}

tasks.check {
    dependsOn(tasks.named("detekt"))
}
