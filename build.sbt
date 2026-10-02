enablePlugins(JavaAppPackaging)

name := "webjars-file-service"

scalaVersion := "3.9.0"

scalacOptions ++= Seq(
  "-language:strictEquality",
)

libraryDependencies ++= Seq(
  "com.jamesward" %% "zio-mavencentral" % "0.14.0",

  "org.slf4j" % "slf4j-simple" % "2.0.20",

  "dev.zio" %% "zio-test"     % "2.1.26" % Test,
  "dev.zio" %% "zio-test-sbt" % "2.1.26" % Test,
)

// This is a deployed service, not a published library, so we don't need
// Scaladoc. Disabling it stops the `doc` task from processing sources —
// which is where the unresolved `[[JarCache]]` doclink warning came from
// (the `compile` task never emitted it) — and skips packaging a doc jar.
Compile / doc / sources := Seq.empty
Compile / packageDoc / publishArtifact := false

// sbt-mcp (loopback-only: its tools can execute build tasks)
Global / mcpEnabled := true
Global / mcpHost := "127.0.0.1"
Global / mcpPort := 5115

// SkillsJars: extract agent Skills with `./sbt extractSkillsJars`
skillsJarsOutputDir := Some(file(".kiro/skills"))

libraryDependencies += "com.jamesward" % "skills" % "0.0.10" % Skills
