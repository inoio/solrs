val p = "foo:(a|b|x)"r

p.findAllMatchIn("foo:a,foo:b,bar:c,foo:x").foreach { m =>
  println(m.group(1))
}