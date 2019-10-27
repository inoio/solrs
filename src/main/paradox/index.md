@@@ index

* [Basic Usage](usage/index.md)
* [Adding Data](usage/adding-data.md)
* [Load Balancing](usage/load-balancing.md)
* [SolrCloud Support](usage/solrcloud.md)
* [Retry Policy](usage/retry-policy.md)
* [Request Interception](usage/request-interception.md)
* [Metrics](usage/metrics.md)

@@@

# solrs - async solr client for java/scala

This is a java/scala solr client providing an interface like SolrJ, just asynchronously / non-blocking
(built on top of [async-http-client](https://github.com/AsyncHttpClient/async-http-client) / [netty](https://github.com/netty/netty)).

The github repo is [inoio/solrs](https://github.com/inoio/solrs), for questions please [join the chat](https://gitter.im/inoio/solrs).

## Key Features

* Async, non-blocking API to Solr on the JVM: supports `CompletableFuture` for Java, for Scala you can choose between Twitter's `Future` or the standard/SDK `Future`.
* SolrCloud support
* Optimized request routing (e.g. updates go to leaders, `_route_` param is respected, `replica.type` is supported for `shards.preference` param)
* Pluggable load balancing strategies, comes with a performance/statistics based load balancer
* Support for retry policies in case of failures

## Installation

Each solrs version is compatible with a certain Solr version:

* Solr 7.6.x: solrs 2.3.0
* Solr 7.4.x: solrs 2.2.0
* Solr 7.2.x: solrs 2.1.0
* Solr 6.x.x: solrs 2.0.0

You must add the library to the dependencies of the build file:
    
@@dependency[sbt,Maven,Gradle] {
  group="io.ino"
  artifact="solrs_2.12"
  version="$project.version$"
}

solrs is published to maven central for scala 2.11 (up to version 2.3.0), 2.12 and 2.13.

## License

This software is licensed under the Apache 2 license
