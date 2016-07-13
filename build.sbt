name := "webjars-file-service"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.8"

libraryDependencies ++= Seq(
  ws,
  filters,
  "org.webjars" %% "webjars-play" % "2.5.0-2",
  "org.apache.commons" % "commons-io" % "1.3.2",
  "com.bionicspirit" %% "shade" % "1.7.4"
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
