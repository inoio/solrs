credentials += Credentials(
  "Sonatype Nexus Repository Manager",
  "oss.sonatype.org",
  sys.env.getOrElse("OSSRH_USERNAME", ""),
  sys.env.getOrElse("OSSRH_PASSWORD", "")
)
