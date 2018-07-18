## Retry Policy

For the case that a request fails `AsyncColrClient` allows to configure a `RetryPolicy`. The following are
currently provided (you can implement your own of course):

* `RetryPolicy.TryOnce`: Don't retry at all
* `RetryPolicy.TryAvailableServers`: Try all servers by fetching the next server from the `LoadBalancer`.
  When requests for all servers failed, the last failure is propagated to the client.
* `RetryPolicy.AtMost(times: Int)`: Retries the given number of times.

The retry policy can be configured via the `Builder`, like this:

@@snip [RetryPolicy.scala](../resources/RetryPolicy.scala) { #intro }

There's not yet support for delaying retries, raise an issue or submit a pull request for this if you need it.