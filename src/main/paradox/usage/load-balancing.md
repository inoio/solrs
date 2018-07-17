## Load Balancing

Solrs supports load balancing of queries over multiple solr servers. There are 2 load balancers provided out of the box,
a simple round robin LB and statistics based LB that selects the "fastest" server. If none of them is suitable for you, of course you can write your own
load balancer by implementing `io.ino.solrs.LoadBalancer`.

### Round Robin Load Balancer

The `RoundRobinLB` is a simple round robin load balancer. It load balances over a given list of solr server urls (if statically known),
alternatively you can provide a `SolrServers` instance, which allows runtime resolution of solr server urls, e.g. by
reading them from ZooKeeper (see @ref:[Solr Cloud Support](solrcloud.md) for more details).

To run solrs with a `RoundRobinLB` you have to pass it to the `Builder`

Java
: @@snip [LoadBalancing.java](../resources/LoadBalancing.java) { #round_robin }

Scala
: @@snip [LoadBalancing.scala](../resources/LoadBalancing.scala) { #round_robin }

### Fastest Server Load Balancer

The `FastestServerLB` is a statistics based load balancer that classifies servers as "fast" and "slow" servers (based on
their latest average response time) and selects one of the "fast" servers (round robin) when asked for one.
This is useful e.g. when some solr server is currently performing major GC, or when for some nodes network latency is
increased (temporary or permanent).

The latest average response time is determined in the following order (the first found measure is used):

1. currently still running requests (if they're lasting longer than previous, already completed requests)
2. average response time of the current or the previous second
3. average response time of the last ten seconds
4. total average resonse time

The response time is measured using a configured test query (per collection). A dedicated test query is used, because
user queries can have very different performance characteristics, so that most often it would even be hard for an application to classify them. With the dedicated test query you can control what is used to measure response time.

Servers are considered "fast" when the response time is <= the average response time of all servers. This is the
default, you can also override this (by specifying a `filterFastServers` function).

Because nobody likes log spamming and burning CPU time while everybody else is sleeping, the test query is not executed with a fixed rate.<br/>
For "fast" servers test queries are run whenever a request comes in, with a lower bound of `minDelay` (default: 100 millis). With high
traffic this leads to high resolution statistics so that e.g. sub-second GC pauses should be detected.<br/>
For "slow" servers (response time > average) tests are run with a fixed `maxDelay` (default: 10 seconds), this is
also the case for "fast" servers when there are no users queries in the meantime.

To have initial stats, after the `FastestServerLB` was created it runs the test queries several times (default: 10).
This can be overridden with `initialTestRuns`.

@@@ note { title=Hint }
`FastestServerLB` also exports stats via JMX (under object name `io.ino.solrs:type=FastestServerLB`), in case you're interested in this.
@@@

Here's  a code sample of the `FastestServerLB`:

Java
: @@snip [LoadBalancing.java](../resources/LoadBalancing.java) { #fastest_server }

Scala
: @@snip [LoadBalancing.scala](../resources/LoadBalancing.scala) { #fastest_server }
