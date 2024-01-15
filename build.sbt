enablePlugins(PlayScala)

name := "webjars-file-service"

scalaVersion := "2.13.12"

libraryDependencies ++= Seq(
  ws,
  guice,
  filters,
  cacheApi,

  "org.webjars" %% "webjars-play" % "3.0.1",
  "commons-io" % "commons-io" % "2.15.1",
  "com.github.mumoshu" %% "play2-memcached-play28" % "0.11.0",
  "org.scalatestplus.play" %% "scalatestplus-play" % "7.0.1" % Test,
  "com.dimafeng" %% "testcontainers-scala-scalatest" % "0.41.0" % Test
)

javacOptions ++= Seq("--release", "11")

Test / fork := true
