name := "solrs"

description := "A solr client for scala, providing a query interface like SolrJ, just asynchronously / non-blocking"

homepage := Some(url("https://github.com/inoio/solrs"))

organization := "io.ino"

version := "1.0.0-RC6"

licenses := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.html"))

scalaVersion := "2.10.4"

crossScalaVersions := Seq("2.10.4", "2.11.1")

resolvers += "JCenter" at "http://jcenter.bintray.com/"

libraryDependencies ++= Seq(
  "org.apache.solr" % "solr-solrj" % "4.8.1" exclude("org.apache.zookeeper", "zookeeper"),
  "com.ning" % "async-http-client" % "1.8.8",
  "com.codahale.metrics" % "metrics-core" % "3.0.2" % "optional",
  "org.slf4j" % "slf4j-api" % "1.7.5",
  "org.slf4j" % "slf4j-simple" % "1.7.5" % "test",
  "org.scalatest" %% "scalatest" % "2.2.0" % "test",
  "org.mockito" % "mockito-core" % "1.9.5" % "test",
  "org.clapper" %% "grizzled-scala" % "1.1.6" % "test",
  // tomcat
  "org.apache.tomcat" % "tomcat-catalina" % "7.0.52" % "test",
  "org.apache.tomcat" % "tomcat-jasper" % "7.0.52" % "test",
  "org.apache.tomcat.embed" % "tomcat-embed-core" % "7.0.52" % "test",
  "commons-logging" % "commons-logging" % "1.1.1"
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
