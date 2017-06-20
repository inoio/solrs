addSbtPlugin("org.scoverage" % "sbt-scoverage" % "1.5.0")
addSbtPlugin("io.get-coursier" % "sbt-coursier" % "1.0.0-RC4")

resolvers += Resolver.bintrayIvyRepo("scalacenter", "sbt-releases")
addSbtPlugin("org.scala-sbt" % "sbt-zinc-plugin" % "1.0.0-X10")
