enablePlugins(JavaAppPackaging)

name := "webjars-file-service"

scalaVersion := "3.8.4"

scalacOptions ++= Seq(
  "-language:strictEquality",
)

libraryDependencies ++= Seq(
  "com.jamesward" %% "zio-mavencentral" % "0.12.0",

  "org.slf4j" % "slf4j-simple" % "2.0.18",

  "dev.zio" %% "zio-test"     % "2.1.26" % Test,
  "dev.zio" %% "zio-test-sbt" % "2.1.26" % Test,
)

// This is a deployed service, not a published library, so we don't need
// Scaladoc. Disabling it stops the `doc` task from processing sources —
// which is where the unresolved `[[JarCache]]` doclink warning came from
// (the `compile` task never emitted it) — and skips packaging a doc jar.
Compile / doc / sources := Seq.empty
Compile / packageDoc / publishArtifact := false
