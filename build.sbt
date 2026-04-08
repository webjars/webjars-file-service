enablePlugins(JavaAppPackaging)

name := "webjars-file-service"

scalaVersion := "3.8.3"

scalacOptions ++= Seq(
  "-language:strictEquality",
)

libraryDependencies ++= Seq(
  "com.jamesward" %% "zio-mavencentral" % "0.5.9",

  "org.slf4j" % "slf4j-simple" % "2.0.17",

  "dev.zio" %% "zio-test"     % "2.1.25" % Test,
  "dev.zio" %% "zio-test-sbt" % "2.1.25" % Test,
)

fork := true
