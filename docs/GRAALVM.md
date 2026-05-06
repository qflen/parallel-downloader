# GraalVM native-image

The project supports compiling parallel-downloader to a self-contained native binary via
the [GraalVM Native Build Tools](https://graalvm.github.io/native-build-tools/) Gradle
plugin. Native compile is opt-in: the plugin only loads when `-PnativeImage=true` is set,
so the default `./gradlew check` path doesn't pull in GraalVM tooling for users who only
want the JVM build.

## Build

Prerequisites:

- GraalVM 21+ on `JAVA_HOME` (or Mandrel as a CE-only fork)
- The `native-image` tool installed (`gu install native-image` on classic GraalVM)

Then:

```bash
./gradlew -PnativeImage=true nativeCompile
./build/native/nativeCompile/parallel-downloader \
    https://example.com/big.bin /tmp/big.bin \
    --chunk-size 8MiB --parallelism 8
```

Run the regular tests against a native binary:

```bash
./gradlew -PnativeImage=true nativeTest
```

## What works

The runtime classpath is small and reflection-light: `kotlinx-coroutines-core` plus the JDK.
`java.net.http.HttpClient`, `java.nio.channels.FileChannel`, and the kotlinx-coroutines
suspending primitives are well-supported by GraalVM's native-image. Most of the project
should compile cleanly at default settings.

## What may fail

GraalVM's static analysis flags reflection / dynamic class loading sites. The known
candidates in this project:

- `java.net.http.HttpClient`'s default selector implementation uses reflection on some
  platforms; the GraalVM Native Build Tools' bundled metadata covers this.
- `kotlinx-coroutines` reflection at `Dispatchers.IO` initialization; covered by the
  coroutines library's own `META-INF/native-image` metadata since 1.7.0.
- `java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME` initialization; standard JDK
  metadata covers it.

If a specific reflection site is missing from the metadata bundles, the binary will fail
at runtime with a `ClassNotFoundException` or `MissingResourceException`. Fixing it means
adding a `reflect-config.json` entry under `src/main/resources/META-INF/native-image/`. The
Native Build Tools support running the agent under a regular JVM run to discover sites
automatically:

```bash
# Run the JVM build under the tracing agent to capture metadata
./gradlew -Pagent run --args="https://example.com/big.bin /tmp/big.bin"
# Generated metadata lands in build/native/agent-output/main/
```

## Known limitations

- HTTPS to a wide variety of CAs requires the JDK's `cacerts` bundle. GraalVM bundles a
  copy at native-image time, but a system with non-standard CAs may need
  `--enable-https` or similar flags. The build configures `--enable-https` by default;
  see `nativeCompile` in `build.gradle.kts`.
- File descriptor limits: the native binary respects `ulimit -n`. For a download with
  `--parallelism 32`, set `ulimit -n 1024` minimum. The JVM build's tests have the same
  property.
- Log frameworks: the project intentionally ships zero logging dependencies on the
  runtime classpath. The native binary inherits that.

## CI

The release workflow (`.github/workflows/release.yml`) does NOT run native-compile by
default; the binary is platform-specific (per-OS, per-architecture) and including it in
the matrix would multiply CI time without buying much. Users who want a native binary
build it locally; the project's release artifacts are the JVM-portable distZip / distTar /
jar.

A future improvement would be a separate `native-release.yml` workflow that runs
`-PnativeImage=true nativeCompile` on `ubuntu-latest`, `macos-latest`, and
`windows-latest`, and uploads the platform-specific binaries as additional release
artifacts. Out of scope for this branch.
