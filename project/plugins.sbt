ThisBuild / libraryDependencySchemes ++= Seq(
  "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always
)

addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.0.11")
addSbtPlugin("com.github.sbt" % "sbt-site-paradox" % "1.7.0")
addSbtPlugin("com.github.sbt" % "sbt-ghpages" % "0.8.0")
// use a newer version of paradox (site would pull in automatically an older version)
addSbtPlugin("com.lightbend.paradox" % "sbt-paradox" % "0.10.7")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.12.1")
// addSbtPlugin("io.github.jonas" % "sbt-paradox-material-theme" % "0.4.0")
addDependencyTreePlugin
/*
resolvers += Resolver.bintrayIvyRepo("scalacenter", "sbt-releases")
addSbtPlugin("org.scala-sbt" % "sbt-zinc-plugin" % "1.0.0-X10")
*/
