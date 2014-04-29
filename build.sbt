name := "solrs"

description := "A solr client for scala, providing a query interface like SolrJ, just asynchronously / non-blocking"

organization := "io.ino"

version := "1.0.0-RC3"

licenses := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.html"))

scalaVersion := "2.10.4"

resolvers += "JCenter" at "http://jcenter.bintray.com/"

libraryDependencies ++= Seq(
  "org.apache.solr" % "solr-solrj" % "4.7.1" exclude("org.apache.zookeeper", "zookeeper"),
  "com.ning" % "async-http-client" % "1.8.6",
  "org.slf4j" % "slf4j-api" % "1.7.5",
  "org.slf4j" % "slf4j-simple" % "1.7.5" % "test",
  "org.scalatest" %% "scalatest" % "2.1.0" % "test",
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
seq(bintrayPublishSettings:_*)

publishTo := Some("bintray-magro-maven-solrs"  at "https://api.bintray.com/maven/magro/maven/solrs")

pomExtra := (
  <url>https://github.com/inoio/solrs</url>
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
