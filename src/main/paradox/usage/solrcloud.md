## Solr Cloud Support

Solr Cloud is supported by `io.ino.solrs.CloudSolrServers`, which is a `SolrServers` implementation (can
be passed to `RoundRobinLB`/`FastestServerLB`).

Solr Cloud is supported with the following properties / restrictions:

* Standard collection aliases are supported, no support for (time) routed aliases (see also [Collections API / CreateAlias docs](https://lucene.apache.org/solr/guide/7_4/collections-api.html#createalias))
* Can use a default collection, if this is not provided, per request the `SolrQuery` must specify
  the collection via the "collection" parameter.
* New solr servers or solr servers that changed their state from inactive (e.g. down) to active can be tested
  with warmup queries before they're used for load balancing queries, for this a `WarmupQueries` instance
  can be set.
* Querying solr is possible when ZooKeeper is temporarily not available
* Construction of `CloudSolrServers` is possible while ZooKeeper is not available. When ZK becomes
  available `CloudSolrServers` will be connected to ZK. As interval for trying to connect the
  `CloudSolrServers.zkConnectTimeout` property is (re)used (10 seconds by default). Connection is tried "forever",
  but of course this does not prevent `CloudSolrServers` or `AsyncSolrClient` to be shutdown.
* Construction of `CloudSolrServers` is possible when no solr instances are known by ZK. When solr servers
  have registered at ZK, `CloudSolrServers` will notice this. As retry interval the `CloudSolrServers.clusterStateUpdateInterval`
  property is (re)used (1 second by default).
* ZK cluster state updates are read using the `CloudSolrServers.clusterStateUpdateInterval`.

To run solrs connected to SolrCloud / ZooKeeper, you pass an instance of `CloudSolrServers` to `RoundRobinLB`/`FastestServerLB`.
The simplest case looks like this:

Java
: @@snip [SolrCloud.java](../resources/SolrCloud.java) { #intro }

Scala
: @@snip [SolrCloud.scala](../resources/SolrCloud.scala) { #intro }

Here's an example that shows all configuration properties in use:

Java
: @@snip [SolrCloud.java](../resources/SolrCloud.java) { #extended }

Scala
: @@snip [SolrCloud.scala](../resources/SolrCloud.scala) { #extended }

@@@ note { title=Note }
Remember to either specify a default collection (as shown above) or set the collection to use per query
(via `new SolrQuery("scala").setParam("collection", "collection1")`).
@@@

When running SolrCloud you should also configure a retry policy (`RetryPolicy.TryAvailableServers` to be concrete),
because restarts of solr nodes are not that fast registered by ZooKeeper (and therefore also not by our `CloudSolrServers`),
so that for a short period of time queries might be failing because a solr node just became not available.