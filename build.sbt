enablePlugins(PlayScala)

name := "webjars-file-service"

scalaVersion := "2.13.16"

libraryDependencies ++= Seq(
  ws,
  guice,
  filters,
  cacheApi,

  // https://github.com/playframework/playframework/releases/2.8.15
  "com.google.inject" % "guice" % "5.1.0",
  "com.google.inject.extensions" % "guice-assistedinject" % "5.1.0",

  "org.webjars" %% "webjars-play" % "2.8.18",
  "org.apache.commons" % "commons-io" % "1.3.2",
  "com.github.mumoshu" %% "play2-memcached-play28" % "0.11.0",
  "org.scalatestplus.play" %% "scalatestplus-play" % "7.0.2" % Test,
  "com.dimafeng" %% "testcontainers-scala-scalatest" % "0.43.0" % Test
)

javacOptions ++= Seq("-source", "1.8", "-target", "1.8")

Test / fork := true
