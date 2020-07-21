name := "solrs"

description := "A solr client for scala, providing a query interface like SolrJ, just asynchronously / non-blocking"

homepage := Some(url("https://github.com/inoio/solrs"))

organization := "io.ino"

// version is defined in version.sbt

scmInfo := Some(ScmInfo(url("https://github.com/inoio/solrs"), "git@github.com:inoio/solrs.git"))

licenses := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.html"))

scalaVersion := "2.12.11"

crossScalaVersions := Seq("2.12.11", "2.13.2")

scalacOptions ++= Seq(
  "-unchecked",
  "-deprecation",
  "-feature",
  "-language:existentials",
  "-language:higherKinds",
  "-language:implicitConversions",
  "-language:postfixOps"
)

javacOptions ++= Seq("-source", "1.8", "-target", "1.8", "-Xlint")

// initialize := {
//   val _ = initialize.value
//   if (sys.props("java.specification.version") != "1.8")
//     sys.error(s"Java 8 is required for this project. Running: ${sys.props("java.specification.version")}")
// }

resolvers ++= Seq(
  "Restlet Repositories" at "https://maven.restlet.org"
)

val solrVersion = "8.5.2"
val slf4jVersion = "1.7.30"

libraryDependencies ++= Seq(
  "org.apache.solr"         % "solr-solrj"        % solrVersion,
  "org.asynchttpclient"     % "async-http-client" % "2.12.1",
  "org.scala-lang.modules" %% "scala-xml"         % "1.3.0",
  "org.scala-lang.modules" %% "scala-java8-compat"% "0.9.1",
  "io.dropwizard.metrics"   % "metrics-core"      % "3.2.6" % "optional",
  "org.slf4j"               % "slf4j-api"         % slf4jVersion,
  "org.slf4j"               % "slf4j-simple"      % slf4jVersion % "test",
  "org.scalatest"          %% "scalatest"         % "3.0.8" % "test",
  "com.novocode"            % "junit-interface"   % "0.11" % "test",
  "org.mockito"             % "mockito-core"      % "1.10.19" % "test",
  "org.hamcrest"            % "hamcrest-library"  % "1.3" % "test",
  "org.apache.solr"         % "solr-test-framework" % solrVersion % "test" excludeAll(ExclusionRule(organization = "org.apache.logging.log4j")),
  "com.twitter"            %% "util-core"         % "19.11.0" % "optional"
)

// Fork tests so that SolrRunner's shutdown hook kicks in
fork in Test := true

enablePlugins(ParadoxSitePlugin)
sourceDirectory in Paradox := sourceDirectory.value / "main" / "paradox"

enablePlugins(GhpagesPlugin)
git.remoteRepo := scmInfo.value.get.connection

enablePlugins(ParadoxPlugin)
paradoxTheme := Some(builtinParadoxTheme("generic"))
paradoxGroups := Map("Language" -> Seq("Scala", "Java"))

/*
// paradoxGroups switcher not aligned: https://github.com/jonas/paradox-material-theme/issues/11
enablePlugins(ParadoxMaterialThemePlugin)
paradoxMaterialTheme in Compile := {
  ParadoxMaterialTheme()
    .withoutSearch()
    .withColor("indigo", "orange")
    .withRepository(uri("https://github.com/inoio/solrs"))
}
*/

// Publish settings
publishTo in ThisBuild := {
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
  <developers>
    <developer>
      <id>martin.grotzke</id>
      <name>Martin Grotzke</name>
      <url>https://github.com/magro</url>
    </developer>
  </developers>)
