// The Play plugin
addSbtPlugin("org.playframework" % "sbt-plugin" % "3.0.6")

// https://github.com/sbt/sbt/issues/7007
ThisBuild / libraryDependencySchemes ++= Seq(
  "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
)
