name := "solrs"

description := "A solr client for scala, providing a query interface like SolrJ, just asynchronously / non-blocking"

homepage := Some(url("https://github.com/inoio/solrs"))

organization := "io.ino"

version := "1.0.4-SNAPSHOT"

licenses := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.html"))

scalaVersion := "2.11.6"

crossScalaVersions := Seq("2.10.4", "2.11.4")

// https://github.com/jrudolph/sbt-dependency-graph
// E.g. adds dependency-graph task
net.virtualvoid.sbt.graph.Plugin.graphSettings

resolvers ++= Seq(
  "JCenter" at "http://jcenter.bintray.com/",
  "Restlet Repositories" at "http://maven.restlet.org"
)

// add scala-xml dependency when needed (for Scala 2.11 and newer)
// this mechanism supports cross-version publishing
libraryDependencies := {
  CrossVersion.partialVersion(scalaVersion.value) match {
    case Some((2, scalaMajor)) if scalaMajor >= 11 =>
      libraryDependencies.value :+ "org.scala-lang.modules" %% "scala-xml" % "1.0.2"
    case _ =>
      libraryDependencies.value
  }
}

val solrVersion = "5.0.0"
val slf4jVersion = "1.7.7"
val tomcatVersion = "7.0.57"

libraryDependencies ++= Seq(
  "org.apache.solr" % "solr-solrj" % solrVersion,
  "com.ning" % "async-http-client" % "1.8.15",
  "com.codahale.metrics" % "metrics-core" % "3.0.2" % "optional",
  "org.slf4j" % "slf4j-api" % slf4jVersion,
  "com.typesafe.akka" %% "akka-actor" % "2.3.7",
  "org.slf4j" % "slf4j-simple" % slf4jVersion % "test",
  "org.scalatest" %% "scalatest" % "2.2.2" % "test",
  "org.mockito" % "mockito-core" % "1.10.13" % "test",
  "org.clapper" %% "grizzled-scala" % "1.2" % "test",
  // Cloud testing, solr-core for ZkController (upconfig), curator-test for ZK TestingServer
  "org.apache.solr" % "solr-core" % solrVersion % "test",
  "org.apache.curator" % "curator-test" % "2.7.0" % "test",
  // tomcat
  "org.apache.tomcat" % "tomcat-catalina" % tomcatVersion % "test",
  "org.apache.tomcat" % "tomcat-jasper" % tomcatVersion % "test",
  "org.apache.tomcat.embed" % "tomcat-embed-core" % tomcatVersion % "test",
  "com.twitter" %% "util-collection" % "6.23.0" % "test",
  "commons-logging" % "commons-logging" % "1.2"
)

// Fork tests so that SolrRunner's shutdown hook kicks in
fork in Test := true

// Publish settings
publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases" at nexus + "service/local/staging/deploy/maven2")
}

publishMavenStyle := true

publishArtifact in Test := false

// enable publishing the jar produced by `test:package`
publishArtifact in (Test, packageBin) := true

pomIncludeRepository := { _ => false }

pomExtra := (
  <scm>
    <url>git@github.com:inoio/solrs.git</url>
    <connection>scm:git:git@github.com:inoio/solrs.git</connection>
  </scm>
  <developers>
    <developer>
      <id>martin.grotzke</id>
      <name>Martin Grotzke</name>
      <url>https://github.com/magro</url>
    </developer>
  </developers>)

publishTo <<= version { (v: String) =>
  val nexus = "http://nexus.rnd.unicredit.eu/"
  if (v.trim.endsWith("SNAPSHOT"))
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("3rdparty"  at nexus + "content/repositories/thirdparty")
}

credentials += Credentials(Path.userHome / ".ivy2" / ".credentials")
