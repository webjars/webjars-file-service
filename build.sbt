name := "webjars-file-service"

scalaVersion := "3.3.0"

val zioVersion = "2.0.13"

libraryDependencies ++= Seq(
  "dev.zio" %% "zio"                 % zioVersion,
  "dev.zio" %% "zio-concurrent"      % zioVersion,
  "dev.zio" %% "zio-cache"           % "0.2.3",
  "dev.zio" %% "zio-logging"         % "2.1.13",
  "dev.zio" %% "zio-direct"          % "1.0.0-RC7",
  "dev.zio" %% "zio-direct-streams"  % "1.0.0-RC7",
  "dev.zio" %% "zio-http"            % "3.0.0-RC2",
  "dev.zio" %% "zio-redis"           % "0.2.0",
  "dev.zio" %% "zio-schema-protobuf" % "0.4.11",

  "org.slf4j" % "slf4j-simple" % "2.0.7",

  "com.jamesward" %% "zio-mavencentral" % "0.0.2",

  "dev.zio" %% "zio-test"           % zioVersion % Test,
  "dev.zio" %% "zio-test-sbt"       % zioVersion % Test,
  "dev.zio" %% "zio-test-magnolia"  % zioVersion % Test,
  "com.dimafeng" %% "testcontainers-scala-core" % "0.40.17" % Test,
)

resolvers ++= Resolver.sonatypeOssRepos("staging")

fork := true
