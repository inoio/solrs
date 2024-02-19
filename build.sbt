name := "solrs"

description := "A solr client for scala, providing a query interface like SolrJ, just asynchronously / non-blocking"

homepage := Some(url("https://github.com/inoio/solrs"))

organization := "io.ino"

// version is defined in version.sbt

scmInfo := Some(ScmInfo(url("https://github.com/inoio/solrs"), "git@github.com:inoio/solrs.git"))

licenses := Seq("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0.html"))

scalaVersion := "3.3.1"

// Remember: also update scala versions in .travis.yml!
crossScalaVersions := Seq("2.12.18", "2.13.12", "3.3.1")

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

initialize := {
  val _ = initialize.value
  val javaVersion = sys.props("java.specification.version")
  if (javaVersion != "1.8" && javaVersion.toDouble < 9)
    sys.error(s"At least java 8 is required for this project. Running: $javaVersion")
}

resolvers ++= Seq(
  "Restlet Repositories" at "https://maven.restlet.org"
)

val solrVersion = "9.4.1"
val slf4jVersion = "2.0.11"

libraryDependencies ++= Seq(
  "org.apache.solr"         % "solr-solrj"        % solrVersion,
  "org.asynchttpclient"     % "async-http-client" % "2.12.3",
  "org.scala-lang.modules" %% "scala-xml"         % "2.2.0",
  "org.scala-lang.modules" %% "scala-java8-compat"% "1.0.2",
  "io.dropwizard.metrics"   % "metrics-core"      % "4.2.25" % "optional",
  "org.slf4j"               % "slf4j-api"         % slf4jVersion,
  "org.slf4j"               % "slf4j-simple"      % slf4jVersion % "test",
  "org.scalatest"          %% "scalatest"         % "3.2.17" % "test",
  "org.scalatestplus"      %% "mockito-4-6"       % "3.2.15.0" % "test",
  "org.scalatestplus"      %% "junit-4-13"        % "3.2.18.0" % "test",
  "com.github.sbt"          % "junit-interface"   % "0.13.3" % Test,
  "org.hamcrest"            % "hamcrest-library"  % "2.2" % "test",
  "dev.zio"                %% "izumi-reflect"     % "2.3.8" % Test,
  "org.apache.solr"         % "solr-test-framework" % solrVersion % "test" excludeAll(ExclusionRule(organization = "org.apache.logging.log4j")),
  "com.twitter"            %% "util-core"         % "23.11.0" % "optional"
)

excludeDependencies ++= (
  if (scalaVersion.value.startsWith("2.12")) Seq()
  else Seq("org.scala-lang.modules" %% "scala-collection-compat")
)

// Fork tests so that SolrRunner's shutdown hook kicks in
Test / fork := true
enablePlugins(ParadoxSitePlugin)
Paradox / sourceDirectory := sourceDirectory.value / "main" / "paradox"

// prevent linter warning "there's a key that's not used by any other settings/tasks"
Global / excludeLintKeys += Paradox / sourceDirectory

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
ThisBuild / publishTo := {
  val nexus = "https://oss.sonatype.org/"
  if (isSnapshot.value)
    Some("snapshots" at nexus + "content/repositories/snapshots")
  else
    Some("releases" at nexus + "service/local/staging/deploy/maven2")
}

ThisBuild / versionScheme := Some("early-semver")

publishMavenStyle := true

Test / publishArtifact := false

// enable publishing the jar produced by `test:package`
Test / packageBin / publishArtifact := true

pomIncludeRepository := { _ => false }

pomExtra := (
  <developers>
    <developer>
      <id>martin.grotzke</id>
      <name>Martin Grotzke</name>
      <url>https://github.com/magro</url>
    </developer>
  </developers>)
