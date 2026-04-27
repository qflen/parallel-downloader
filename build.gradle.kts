import com.github.jk1.license.render.InventoryMarkdownReportRenderer
import io.gitlab.arturbosch.detekt.Detekt
import java.math.BigDecimal

plugins {
    kotlin("jvm") version "2.0.21"
    application
    jacoco
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
    id("me.champeau.jmh") version "0.7.2"
    id("info.solidsoft.pitest") version "1.15.0"
    id("com.github.jk1.dependency-license-report") version "2.9"
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
// Stress tests and JMH benchmarks each live in their own source set so `./gradlew test`
// stays fast. Both inherit main + test outputs (so they can reuse TestHttpServer / Jetty /
// Bytes) and have their own dedicated task wired up below. The me.champeau.jmh plugin
// pre-creates a `jmh` source set; we just retarget its kotlin sources to src/bench/kotlin.
sourceSets {
    create("stressTest") {
        kotlin.srcDir("src/stressTest/kotlin")
        compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
        runtimeClasspath += output + compileClasspath
    }
    named("jmh") {
        kotlin.srcDir("src/bench/kotlin")
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
val jmhImplementation: Configuration by configurations.getting {
    extendsFrom(configurations.testImplementation.get())
}
val jmhRuntimeOnly: Configuration by configurations.getting {
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
    // jqwik registers as a JUnit Platform engine via ServiceLoader; useJUnitPlatform() picks
    // it up alongside Jupiter without any extra wiring.
    testImplementation("net.jqwik:jqwik:1.9.1")
    // Jetty pulls slf4j-api as a transitive but ships no binding; without one, slf4j prints
    // a "No SLF4J providers were found" warning on first use. The NOP binding silences it
    // without affecting test/bench logic. testRuntimeOnly only - production runtime
    // classpath stays kotlinx-coroutines-only.
    testRuntimeOnly("org.slf4j:slf4j-nop:2.0.16")
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

// ----- JMH -------------------------------------------------------------------
// Benchmarks aren't part of `check` - they're long-running and platform-sensitive.
// Run on demand: `./gradlew jmh`. Per-class @Warmup / @Measurement / @Fork / @Mode
// annotations drive the iteration counts; the plugin DSL only sets the cross-cutting
// knobs (output format / location) and a couple of property bridges so triage one-liners
// from a regression report don't require editing this file.
jmh {
    resultFormat.set("JSON")
    resultsFile.set(layout.buildDirectory.file("reports/jmh/results.json"))
    // Bridges from Gradle properties to the plugin DSL. Examples:
    //   ./gradlew jmh -Pjmh.includes=ParallelismScalingBenchmark
    //   ./gradlew jmh -Pjmh.profilers=gc
    //   ./gradlew jmh -Pjmh.includes=ChunkSizeBenchmark -Pjmh.profilers=gc,stack
    val incl = project.findProperty("jmh.includes") as? String
    if (!incl.isNullOrBlank()) includes.set(incl.split(",").map(String::trim).filter(String::isNotEmpty))
    val profs = project.findProperty("jmh.profilers") as? String
    if (!profs.isNullOrBlank()) profilers.set(profs.split(",").map(String::trim).filter(String::isNotEmpty))
}

tasks.named<me.champeau.jmh.JMHTask>("jmh") {
    // The 100 MiB benchmarks need real heap headroom; Gradle's worker default can OOM under
    // burst allocation. The forked JMH JVMs inherit these args. (JaCoCo's auto-attach
    // targets JavaExec - JMHTask extends DefaultTask, so the agent is never instrumenting
    // these forks in the first place.)
    jvmArgs.add("-Xmx2g")
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
            "src/bench/kotlin",
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

// ----- PII scanner -----------------------------------------------------------
// Static check for PII patterns bleeding into source: emails, non-loopback IPs, identifying
// env/property reads, hardcoded user paths, getLocalHost(). The scanner lives under
// src/test/kotlin (com.example.downloader.internal.PiiScanner) so it's testable as a JUnit
// unit; we expose it as a Gradle task by JavaExec'ing through the test runtime classpath.
val piiScan by tasks.registering(JavaExec::class) {
    description = "Static-scans source for PII patterns (emails, IPs, env reads, user paths)."
    group = "verification"
    dependsOn(tasks.compileTestKotlin)
    mainClass.set("com.example.downloader.internal.PiiScannerCli")
    classpath = sourceSets.test.get().runtimeClasspath
    args = listOf(
        "--project-root", projectDir.absolutePath,
        "src/main/kotlin",
        "src/test/kotlin",
        "src/stressTest/kotlin",
        "src/bench/kotlin",
    )
}

tasks.check {
    dependsOn(piiScan)
}

// ----- License report --------------------------------------------------------
// Generates LICENSES.md at the repo root, scoped to the runtime classpath only (the
// transitive bundle that ships with the production jar). build/ runs the report and
// then a Copy task syncs the rendered Markdown to LICENSES.md so the human-readable
// snapshot stays current under version control.
licenseReport {
    configurations = arrayOf("runtimeClasspath")
    renderers = arrayOf(InventoryMarkdownReportRenderer("LICENSES.md", "parallel-downloader"))
    outputDir = layout.buildDirectory.dir("reports/dependency-license").get().asFile.absolutePath
}

val syncLicensesMd by tasks.registering(Copy::class) {
    description = "Refreshes the repo-root LICENSES.md from the latest license report."
    group = "verification"
    dependsOn("generateLicenseReport")
    from(layout.buildDirectory.dir("reports/dependency-license")) {
        include("LICENSES.md")
    }
    into(projectDir)
    // The renderer stamps a wall-clock timestamp on line 4 ("_2026-04-27 22:17:03 CEST_").
    // Strip it during the copy so the committed snapshot only changes when the dependency
    // set changes, not on every build.
    filter { line: String ->
        if (line.matches(Regex("""^_\d{4}-\d{2}-\d{2}\s.*_$"""))) "" else line
    }
}

tasks.build {
    dependsOn(syncLicensesMd)
}

// ----- Pitest (mutation testing) ---------------------------------------------
// On-demand only: `./gradlew pitest`. Not part of `check` because a full mutation
// run takes several minutes and is sensitive to JIT noise. Excludes mirror JaCoCo's
// (Main / Cli* are exercised end-to-end manually, not via mutation-testable units).
pitest {
    targetClasses.set(listOf("com.example.downloader.*"))
    excludedClasses.set(
        listOf(
            "com.example.downloader.MainKt",
            "com.example.downloader.MainKt\$*",
            "com.example.downloader.Cli*",
        )
    )
    targetTests.set(listOf("com.example.downloader.*"))
    threads.set(4)
    junit5PluginVersion.set("1.2.1")
    outputFormats.set(listOf("HTML", "XML"))
    timestampedReports.set(false)
    timeoutFactor.set(BigDecimal.valueOf(2))
}
