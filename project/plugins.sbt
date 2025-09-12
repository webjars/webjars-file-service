// The Play plugin
addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.9.9")

// https://github.com/sbt/sbt/issues/7007
ThisBuild / libraryDependencySchemes ++= Seq(
  "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
)
