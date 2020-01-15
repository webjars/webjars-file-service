enablePlugins(PlayScala)

name := "webjars-file-service"

scalaVersion := "2.13.1"

libraryDependencies ++= Seq(
  ws,
  guice,
  filters,
  cacheApi,
  "org.webjars" %% "webjars-play" % "2.8.0",
  "org.apache.commons" % "commons-io" % "1.3.2",
  "com.github.mumoshu" %% "play2-memcached-play27" % "0.10.1"
)

javacOptions ++= Seq("-source", "1.8", "-target", "1.8")

onLoad in Global := (onLoad in Global).value.andThen { state =>
  if (sys.props("java.specification.version") != "1.8") {
    sys.error("Java 8 is required for this project.")
    state.exit(ok = false)
  }
  else {
    state
  }
}
