addSbtPlugin("com.github.sbt" % "sbt-pgp" % "2.2.1")

credentials += Credentials(
  "GnuPG Key ID",
  "gpg",
  "96558BFCB430938C", // key identifier, which is imported from secrets.GPG_PRIVATE_KEY
  "ignored" // this field is ignored; passwords are supplied by pinentry if set
)