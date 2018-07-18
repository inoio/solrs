## Basic Usage

Solrs supports different `Future` implementations, which affects the result type of `AsyncSolrClient` methods.
For scala there's support for the standard `scala.concurrent.Future` and for twitters `com.twitter.util.Future`.
Which one is chosen is defined by the `io.ino.solrs.future.FutureFactory` that's in scope when building the `AsyncSolrClient` (as shown
below in the code samples).

For java there's support for `CompletableFuture`/`CompletionStage`. Because java does not support higher kinded types there's
a separate class `JavaAsyncSolrClient` that allows to create new instances and to perform a request.

In the following it's shown how to use `JavaAsyncSolrClient`/`AsyncSolrClient`:

Java
: @@snip [HelloJava.java](../resources/HelloJava.java)

Scala
: @@snip [HelloScala.scala](../resources/HelloScala.scala)

Twitter
: @@snip [HelloTwitter.scala](../resources/HelloTwitter.scala)

The `AsyncSolrClient` can further be configured with an `AsyncHttpClient` instance and the response parser
via the `AsyncSolrClient.Builder` (other configuration properties are described in greater detail in the following sections):

Java
: @@snip [BuilderIntro.java](../resources/BuilderIntro.java)

Scala
: @@snip [BuilderIntro.scala](../resources/BuilderIntro.scala)