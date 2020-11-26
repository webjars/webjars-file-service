enablePlugins(PlayScala)

name := "webjars-file-service"

scalaVersion := "2.13.4"

libraryDependencies ++= Seq(
  ws,
  guice,
  filters,
  cacheApi,
  "org.webjars" %% "webjars-play" % "2.8.0",
  "org.apache.commons" % "commons-io" % "1.3.2",
  "com.github.mumoshu" %% "play2-memcached-play28" % "0.11.0",
  "org.scalatestplus.play" %% "scalatestplus-play" % "5.0.0" % Test,
  "com.dimafeng" %% "testcontainers-scala-scalatest" % "0.38.7" % Test
)

javacOptions ++= Seq("-source", "1.8", "-target", "1.8")

Test / fork := true
