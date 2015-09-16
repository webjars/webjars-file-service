name := """webjars-file-service"""

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.11.7"

resolvers += "Spy Repository" at "http://files.couchbase.com/maven2"

libraryDependencies ++= Seq(
  ws,
  filters,
  "org.webjars" %% "webjars-play" % "2.3.0-3",
  "org.apache.commons" % "commons-io" % "1.3.2",
  "com.bionicspirit" %% "shade" % "1.6.0"
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
