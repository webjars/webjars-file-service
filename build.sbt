name := """webjars-file-service"""

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.6"

libraryDependencies ++= Seq(
  ws,
  filters,
  "org.webjars" %% "webjars-play" % "2.3.0-3"
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
