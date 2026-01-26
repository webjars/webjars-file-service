enablePlugins(JavaAppPackaging)

name := "webjars-file-service"

scalaVersion := "3.8.1"

scalacOptions ++= Seq(
  "-language:strictEquality",
)

val zioVersion = "2.1.24"

libraryDependencies ++= Seq(
  "dev.zio" %% "zio"            % zioVersion,
  "dev.zio" %% "zio-streams"    % zioVersion,
  "dev.zio" %% "zio-concurrent" % zioVersion,
  "dev.zio" %% "zio-http"       % "3.8.0",
  "dev.zio" %% "zio-streams-compress-zip" % "1.1.3",

  "com.jamesward" %% "zio-mavencentral" % "0.4.0",

  "org.slf4j" % "slf4j-simple" % "2.0.17",

  "dev.zio" %% "zio-test"     % zioVersion % Test,
  "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
)

testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")

fork := true
