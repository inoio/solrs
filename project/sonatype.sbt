credentials += Credentials(
  "Sonatype Nexus Repository Manager",
  "central.sonatype.com",
  sys.env.getOrElse("CENTRAL_USERNAME", ""),
  sys.env.getOrElse("CENTRAL_PASSWORD", "")
)
