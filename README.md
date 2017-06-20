# solrs - async solr client for java/scala

[![Build Status](https://travis-ci.org/inoio/solrs.png?branch=master)](https://travis-ci.org/inoio/solrs)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/io.ino/solrs_2.11/badge.svg)](http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22io.ino%22%20AND%20a%3Asolrs*_2.11)
[![Join the chat at https://gitter.im/inoio/solrs](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/inoio/solrs)

This is a java/scala solr client providing an interface like SolrJ, just asynchronously / non-blocking
(built on top of [async-http-client](https://github.com/AsyncHttpClient/async-http-client) / [netty](https://github.com/netty/netty)).
For java it supports `CompletableFuture`, for scala you can choose between twitter's `Future` or the standard/SDK `Future`.

## Contents

- [Installation](#installation)
- [Usage](#usage)
 - [Adding Data to Solr](#adding-data-to-solr)
 - [Load Balancing](#load-balancing)
 - [Solr Cloud / ZooKeeper Support](#solr-cloud--zookeeper-support)
 - [Retry Policy](#retry-policy)
 - [Request Interception / Filtering](#request-interception--filtering)
 - [Metrics](#metrics)
- [License](#license)

## Installation

You must add the library to the dependencies of the build file, e.g. add to `build.sbt`:

    libraryDependencies += "io.ino" %% "solrs" % "2.0.0-RC5"

or for java projects to pom.xml:

    <dependency>
      <groupId>io.ino</groupId>
      <artifactId>solrs_2.12</artifactId>
      <version>2.0.0-RC5</version>
    </dependency>

solrs is published to maven central for both scala 2.11 and 2.12.

## Usage

Solrs supports different `Future` implementations, which affects the result type of `AsyncSolrClient` methods.
For scala there's support for the standard `scala.concurrent.Future` and for twitters `com.twitter.util.Future`.
Which one is chosen is defined by the `io.ino.solrs.future.FutureFactory` that's in scope when building the `AsyncSolrClient` (as shown
below in the code samples).

For java there's support for `CompletableFuture`. Because java does not support higher kinded types there's
a separate class `JavaAsyncSolrClient` that allows to create new instances and to perform a request.

In the following it's shown how to use `JavaAsyncSolrClient`/`AsyncSolrClient`:

[java]
```java
import io.ino.solrs.JavaAsyncSolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import java.util.concurrent.CompletionStage;

JavaAsyncSolrClient solr = JavaAsyncSolrClient.create("http://localhost:8983/solr/collection1");
CompletionStage<QueryResponse> response = solr.query(new SolrQuery("java"));
response.thenAccept(r -> System.out.println("found " + r.getResults().getNumFound() + " docs"));

// At EOL...
solr.shutdown();
```

[scala/SDK]
```scala
import io.ino.solrs.AsyncSolrClient
import io.ino.solrs.future.ScalaFutureFactory.Implicit // or TwitterFutureFactory
import org.apache.solr.client.solrj.response.QueryResponse
import org.apache.solr.client.solrj.SolrQuery
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

val solr = AsyncSolrClient("http://localhost:8983/solr/collection1")
val response: Future[QueryResponse] = solr.query(new SolrQuery("scala"))
response.foreach {
  qr => println(s"found ${qr.getResults.getNumFound} docs")
}

// Don't forget...
solr.shutdown()
```

[scala/twitter]
```scala
import io.ino.solrs.AsyncSolrClient
import io.ino.solrs.future.TwitterFutureFactory.Implicit
import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.response.QueryResponse
import com.twitter.util.Future

val solr = AsyncSolrClient("http://localhost:8983/solr")
val response: Future[QueryResponse] = solr.query(new SolrQuery("scala"))
response.onSuccess {
  qr => println(s"found ${qr.getResults.getNumFound} docs")
}

// Finally...
solr.shutdown()
```

The `AsyncSolrClient` can further be configured with an `AsyncHttpClient` instance and the response parser
via the `AsyncSolrClient.Builder` (other configuration properties are described in greater detail below):

[java]
```java
import io.ino.solrs.JavaAsyncSolrClient;
import org.apache.solr.client.solrj.impl.XMLResponseParser;
import org.asynchttpclient.DefaultAsyncHttpClient;

JavaAsyncSolrClient solr = JavaAsyncSolrClient.builder("http://localhost:8983/solr/collection1")
            .withHttpClient(new DefaultAsyncHttpClient())
            .withResponseParser(new XMLResponseParser())
            .build();
```

[scala]
```scala
import io.ino.solrs.AsyncSolrClient
import io.ino.solrs.future.ScalaFutureFactory.Implicit
import org.apache.solr.client.solrj.impl.XMLResponseParser
import org.asynchttpclient.DefaultAsyncHttpClient

val solr = AsyncSolrClient.Builder("http://localhost:8983/solr/collection1")
            .withHttpClient(new DefaultAsyncHttpClient())
            .withResponseParser(new XMLResponseParser())
            .build
```

### Adding Data to Solr

[java]
```java
import static java.util.Arrays.asList;
import io.ino.solrs.JavaAsyncSolrClient;
import org.apache.solr.common.SolrInputDocument;

JavaAsyncSolrClient solr = JavaAsyncSolrClient.create("http://localhost:8983/solr/collection1");
SolrInputDocument doc1 = new SolrInputDocument();
doc1.addField("id", "id1");
doc1.addField("name", "doc1");
doc1.addField("cat", "cat1");
doc1.addField("price", 10);
SolrInputDocument doc2 = new SolrInputDocument();
doc2.addField("id", "id2");
doc2.addField("name", "doc2");
doc2.addField("cat", "cat1");
doc2.addField("price", 20);
solr.addDocs(asList(doc1, doc2))
    .thenCompose(r -> solr.commit())
    .thenAccept(r -> System.out.println("docs added"));
```

[scala]
```scala
import io.ino.solrs.AsyncSolrClient
import io.ino.solrs.future.ScalaFutureFactory.Implicit // or TwitterFutureFactory
import org.apache.solr.common.SolrInputDocument

val solr = AsyncSolrClient("http://localhost:8983/solr/collection1")
val doc1 = new SolrInputDocument()
doc1.addField("id", "id1")
doc1.addField("name", "doc1")
doc1.addField("cat", "cat1")
doc1.addField("price", 10)
val doc2 = new SolrInputDocument()
doc2.addField("id", "id2")
doc2.addField("name", "doc2")
doc2.addField("cat", "cat1")
doc2.addField("price", 20)
for {
  _ <- solr.addDocs(docs = Iterable(doc1, doc2))
  _ <- solr.commit()
} yield print("docs added")
```

#### Directly adding POJOs to Solr

[java]
```java
import static java.util.Arrays.asList;
import io.ino.solrs.JavaAsyncSolrClient;
import org.apache.solr.client.solrj.beans.Field;

class Item {
    @Field
    String id;
    
    @Field
    String name;

    @Field("cat")
    String category;

    @Field
    float price;
    
    TestBean(String id, String name, String category, float price) {
        this.id = id;
        this.name = name;
        this.category = category;
        this.price = price;
    }    
}

JavaAsyncSolrClient solr = JavaAsyncSolrClient.create("http://localhost:8983/solr/collection1");
Item item1 = new Item("id1", "doc1", "cat1", 10);
Item item2 = new Item("id2", "doc2", "cat1", 20);
solr.addBeans(asList(item1, item2))
    .thenCompose(r -> solr.commit())
    .thenAccept(r -> System.out.println("beans added"));
```

[scala]
```scala
import io.ino.solrs.AsyncSolrClient
import io.ino.solrs.future.ScalaFutureFactory.Implicit // or TwitterFutureFactory
import org.apache.solr.client.solrj.beans.Field
import scala.annotation.meta.field

case class Item(@(Field @field) id: String,
                @(Field @field) name: String,
                @(Field @field)("cat") category: String,
                @(Field @field) price: Float) {
  def this() = this(null, null, null, 0)
}

val solr = AsyncSolrClient("http://localhost:8983/solr/collection1")
val item1 = Item("id1", "doc1", "cat1", 10)
val item2 = Item("id2", "doc2", "cat1", 20)
for {
  _ <- solr.addBeans(beans = Iterable(item1, item2))
  _ <- solr.commit()
} yield print("beans added")
```

### Load Balancing

Solrs supports load balancing of queries over multiple solr servers. There are 2 load balancers provided out of the box,
a simple round robin LB and statistics based LB that selects the "fastest" server. If none of them is suitable for you, of course you can write your own
load balancer by implementing `io.ino.solrs.LoadBalancer`.

#### Round Robin Load Balancer

The `RoundRobinLB` is a simple round robin load balancer. It load balances over a given list of solr server urls (if statically known),
alternatively you can provide a `SolrServers` instance, which allows runtime resolution of solr server urls, e.g. by
reading them from ZooKeeper (see [Solr Cloud Support](#solr-cloud-support) for more details).

To run solrs with a `RoundRobinLB` you have to pass it to the `Builder`

[java]
```java
import io.ino.solrs.*;
import java.util.Arrays;

RoundRobinLB lb = RoundRobinLB.create(Arrays.asList("http://localhost:8983/solr/collection1", "http://localhost:8984/solr/collection1"));
JavaAsyncSolrClient solr = JavaAsyncSolrClient.builder(lb).build();
```

[scala]
```scala
import io.ino.solrs._
import io.ino.solrs.future.ScalaFutureFactory.Implicit

val lb = RoundRobinLB(IndexedSeq("http://localhost:8983/solr/collection1", "http://localhost:8984/solr/collection1"))
val solr = AsyncSolrClient.Builder(lb).build
```

#### Fastest Server Load Balancer

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

`FastestServerLB` also exports stats via JMX (under object name `io.ino.solrs:type=FastestServerLB`), in case you're interested in this.

Here's  a code sample of the `FastestServerLB`:

[java]
```java
import io.ino.solrs.*;
import java.util.Arrays;
import scala.Tuple2;
import static java.util.concurrent.TimeUnit.*;

StaticSolrServers servers = StaticSolrServers.create(Arrays.asList("http://localhost:8983/solr/collection1", "http://localhost:8984/solr/collection1"));
Tuple2<String, SolrQuery> col1TestQuery = new Tuple2<>("collection1", new SolrQuery("*:*").setRows(0));
Function<SolrServer, Tuple2<String, SolrQuery>> collectionAndTestQuery = server -> col1TestQuery;
FastestServerLB<?> lb = FastestServerLB.builder(servers, collectionAndTestQuery)
    .withMinDelay(50, MILLISECONDS)
    .withMaxDelay(5, SECONDS)
    .withInitialTestRuns(50)
    .build();
JavaAsyncSolrClient solr = JavaAsyncSolrClient.builder(lb).build();
```

[scala]
```scala
import io.ino.solrs._
import scala.concurrent.duration._

val lb = {
  val servers = StaticSolrServers(IndexedSeq("http://localhost:8983/solr/collection1", "http://localhost:8984/solr/collection1"))
  val col1TestQuery = "collection1" -> new SolrQuery("*:*").setRows(0)
  def collectionAndTestQuery(server: SolrServer) = col1TestQuery
  new FastestServerLB(servers, collectionAndTestQuery, minDelay = 50 millis, maxDelay = 5 seconds, initialTestRuns = 50)
}
val solr = AsyncSolrClient.Builder(lb).build
```

### Solr Cloud / ZooKeeper Support

Solr Cloud is supported by `io.ino.solrs.CloudSolrServers`, which is a `SolrServers` implementation (can
be passed to `RoundRobinLB`/`FastestServerLB`).

Solr Cloud is supported with the following properties / restrictions:

* No Shard Support (yet, please raise an issue or submit a pull request)
* No Collection Aliases supported (a PR's welcome)
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

[java]
```java
import io.ino.solrs.*;

CloudSolrServers<?> servers = CloudSolrServers.builder("localhost:2181").build();
JavaAsyncSolrClient solr = JavaAsyncSolrClient.builder(new RoundRobinLB(servers)).build();
```

[scala]
```scala
import io.ino.solrs._
import io.ino.solrs.future.ScalaFutureFactory.Implicit

val servers = new CloudSolrServers("localhost:2181")
val solr = AsyncSolrClient.Builder(RoundRobinLB(servers)).build
```

Here's an example that shows all configuration properties in use:

[java]
```java
import io.ino.solrs.*;
import java.util.Collections;
import static java.util.concurrent.TimeUnit.*;

CloudSolrServers<?> servers = CloudSolrServers.builder("host1:2181,host2:2181")
    .withZkClientTimeout(15, SECONDS)
    .withZkConnectTimeout(10, SECONDS)
    .withClusterStateUpdateInterval(1, SECONDS)
    .withDefaultCollection("collection1")
    .withWarmupQueries((collection) -> Collections.singletonList(new SolrQuery("*:*")), 10)
    .build();
JavaAsyncSolrClient solr = JavaAsyncSolrClient.builder(new RoundRobinLB(servers)).build();
```

[scala]
```scala
import io.ino.solrs._
import io.ino.solrs.future.ScalaFutureFactory.Implicit
import scala.concurrent.duration._

val servers = new CloudSolrServers(
    zkHost = "host1:2181,host2:2181",
    zkClientTimeout = 15 seconds,
    zkConnectTimeout = 10 seconds,
    clusterStateUpdateInterval = 1 second,
    defaultCollection = Some("collection1"),
    warmupQueries = WarmupQueries("collection1" => Seq(new SolrQuery("*:*")), count = 10))
val solr = AsyncSolrClient.Builder(RoundRobinLB(servers)).build
```

Remember to either specify a default collection (as shown above) or set the collection to use per query:

[scala]
```scala
import org.apache.solr.client.solrj.SolrQuery

val query = new SolrQuery("scala").setParam("collection", "collection1")
val response = solr.query(query)

```

When running SolrCloud you should also configure a retry policy (`RetryPolicy.TryAvailableServers` to be concrete),
because restarts of solr nodes are not that fast registered by ZooKeeper (and therefore also not by our `CloudSolrServers`),
so that for a short period of time queries might be failing because a solr node just became not available.

### Retry Policy

For the case that a request fails `AsyncColrClient` allows to configure a `RetryPolicy`. The following are
currently provided (you can implement your own of course):

* `RetryPolicy.TryOnce`: Don't retry at all
* `RetryPolicy.TryAvailableServers`: Try all servers by fetching the next server from the `LoadBalancer`.
  When requests for all servers failed, the last failure is propagated to the client.
* `RetryPolicy.AtMost(times: Int)`: Retries the given number of times.

The retry policy can be configured via the `Builder`, like this:

[java]
```java
import io.ino.solrs.*;

JavaAsyncSolrClient solr = JavaAsyncSolrClient.builder(new RoundRobinLB(CloudSolrServers.builder("host1:2181,host2:2181").build()))
    .withRetryPolicy(RetryPolicy.TryAvailableServers())
    .build();
```

[scala]
```scala
import io.ino.solrs._
import io.ino.solrs.future.ScalaFutureFactory.Implicit

val solr = AsyncSolrClient.Builder(RoundRobinLB(new CloudSolrServers("host1:2181,host2:2181")))
    .withRetryPolicy(RetryPolicy.TryAvailableServers)
    .build
```

There's not yet support for delaying retries, raise an issue or submit a pull request for this if you need it.

### Request Interception / Filtering

Solrs allows to intercept queries sent to Solr, here's an example that shows how to log details about each request:

[scala]
```scala
import io.ino.solrs._
import io.ino.solrs.future.ScalaFutureFactory.Implicit

val loggingInterceptor = new RequestInterceptor {
  override def interceptQuery(f: (SolrServer, SolrQuery) => Future[QueryResponse])
                             (solrServer: SolrServer, q: SolrQuery): Future[QueryResponse] = {
    val start = System.currentTimeMillis()
    f(solrServer, q).map { qr =>
      val requestTime = System.currentTimeMillis() - start
      logger.info(s"Query $q to $solrServer took $requestTime ms (query time in solr: ${qr.getQTime} ms).")
      qr
    }
  }
}

val solr = AsyncSolrClient.Builder("http://localhost:8983/solr/collection1")
             .withRequestInterceptor(loggingInterceptor).build
```

### Metrics

There's basic metrics support for request timings and number of exceptions. You can provide your own
implementation of `io.ino.solrs.Metrics` or use the `CodaHaleMetrics` class shipped with solrs if you're
happy with this great [metrics library](http://metrics.codahale.com/) :-)

To configure solrs with the `Metrics` implementation just pass an initialized instance like this:

[scala]
```scala
import io.ino.solrs._
import io.ino.solrs.future.ScalaFutureFactory.Implicit

val solr = AsyncSolrClient.Builder("http://localhost:8983/solr/collection1")
             .withMetrics(new CodaHaleMetrics()).build
```

If you're using Coda Hale's Metrics library and you want to reuse an existing `MetricsRegistry`,
just pass it to the `CodaHaleMetrics` class: `new CodaHaleMetrics(registry)`.

## License

The license is Apache 2.0, see LICENSE.txt.
