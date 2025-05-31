// no longer used, we're now using the default env vars SONATYPE_USERNAME/PASSWORD
// according to https://github.com/sbt/sbt/issues/8146
// this file can be deleted asap, I couldn't do this via the web editor
/*
credentials += Credentials(
  null, // realm
  "central.sonatype.com",
  sys.env.getOrElse("CENTRAL_USERNAME", ""),
  sys.env.getOrElse("CENTRAL_PASSWORD", "")
)
*/
