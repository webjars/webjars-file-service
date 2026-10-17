enablePlugins(JavaAppPackaging)

name := "webjars-file-service"

scalaVersion := "3.10.0"

scalacOptions ++= Seq(
  "-language:strictEquality",
)

libraryDependencies ++= Seq(
  "com.jamesward" %% "zio-mavencentral" % "0.14.1",

  "org.slf4j" % "slf4j-simple" % "2.0.20",

  "dev.zio" %% "zio-test"     % "2.1.27" % Test,
  "dev.zio" %% "zio-test-sbt" % "2.1.27" % Test,
  // Local S3 emulator for tests and `Test/run` without AWS credentials.
  // Excludes its slf4j 1.x binding, which conflicts with slf4j-simple 2.x.
  ("io.floci" % "testcontainers-floci" % "2.16.1" % Test).exclude("org.slf4j", "slf4j-simple"),
)

// `Test/run` starts AppTest: real S3 when AWS credentials are set, otherwise
// a Floci container.
Test / mainClass := Some("AppTest")
Test / run / fork := true

// JVM sizing for a 1 GB Standard-2X dyno (written to the start script's
// conf/application.ini, which overrides Heroku's JAVA_TOOL_OPTIONS -Xmx671m).
// Measured with NMT: non-heap is ~330 MB (Netty pooled direct memory ~100 MB
// on 8 CPUs, G1 structures ~60 MB, metaspace, code cache, threads), so a
// 671 MB heap pushes the process past the quota (R14) once the heap has been
// committed by a traffic burst. Peak post-GC heap under load was ~355 MB.
// Netty's adaptive allocator switches to a slower low-memory mode for heaps
// <= 512 MB; it isn't needed here, so keep the default mode.
Universal / javaOptions ++= Seq(
  "-J-Xmx480m",
  "-Dio.netty.allocator.lowMemory=false",
)

// This is a deployed service, not a published library, so we don't need
// Scaladoc. Disabling it stops the `doc` task from processing sources —
// which is where the unresolved `[[JarCache]]` doclink warning came from
// (the `compile` task never emitted it) — and skips packaging a doc jar.
Compile / doc / sources := Seq.empty
Compile / packageDoc / publishArtifact := false

// SkillsJars: extract agent Skills with `./sbt extractSkillsJars`
skillsJarsOutputDir := Some(file(".kiro/skills"))

libraryDependencies += "com.jamesward" % "skills" % "0.0.16" % Skills
