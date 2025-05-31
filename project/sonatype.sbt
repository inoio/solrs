credentials += Credentials(
  "central.sonatype.com",
  sys.env.getOrElse("CENTRAL_USERNAME", ""),
  sys.env.getOrElse("CENTRAL_PASSWORD", "")
)
