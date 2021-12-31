## Metrics

There's basic metrics support for request timings and number of exceptions. You can provide your own
implementation of `io.ino.solrs.Metrics` or use the `CodaHaleMetrics` class shipped with solrs if you're
just happy with this [metrics library](https://metrics.dropwizard.io/) :-)

To configure solrs with the `Metrics` implementation pass an initialized instance like this:

@@snip [Metrics.scala](../resources/Metrics.scala) { #intro }

If you're using Coda Hale's Metrics library and you want to reuse an existing `MetricsRegistry`,
just pass it to the `CodaHaleMetrics` class: `new CodaHaleMetrics(registry)`.