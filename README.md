# solrs - async solr client for java/scala

[![Build Status](https://github.com/inoio/solrs/actions/workflows/scala.yml/badge.svg)](https://github.com/inoio/solrs/actions/workflows/scala.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.ino/solrs_2.12.svg)](http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22io.ino%22%20AND%20a%3Asolrs*_2.12)
[![Join the chat at https://gitter.im/inoio/solrs](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/inoio/solrs)

This is a java/scala solr client providing an interface like SolrJ, just asynchronously / non-blocking
(built on top of [async-http-client](https://github.com/AsyncHttpClient/async-http-client) / [netty](https://github.com/netty/netty)).

## Key Features

* Async, non-blocking API to Solr on the JVM: supports `CompletableFuture` for Java, for Scala you can choose between Twitter's `Future` or the standard/SDK `Future`.
* SolrCloud support
* Optimized request routing (e.g. updates go to leaders, `_route_` param is respected, `replica.type` is supported for `shards.preference` param)
* Pluggable load balancing strategies, comes with a performance/statistics based load balancer
* Support for retry policies in case of failures

## Documentation

The documentation is available at [https://inoio.github.io/solrs/](https://inoio.github.io/solrs/)

## License

This software is licensed under the Apache 2 license, see LICENSE.txt.
