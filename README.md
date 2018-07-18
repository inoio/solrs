# solrs - async solr client for java/scala

[![Build Status](https://travis-ci.org/inoio/solrs.png?branch=master)](https://travis-ci.org/inoio/solrs)
[![Maven Central](https://img.shields.io/maven-central/v/io.ino/solrs_2.11.svg)](http://search.maven.org/#search%7Cga%7C1%7Cg%3A%22io.ino%22%20AND%20a%3Asolrs*_2.11)
[![Join the chat at https://gitter.im/inoio/solrs](https://badges.gitter.im/Join%20Chat.svg)](https://gitter.im/inoio/solrs)

This is a java/scala solr client providing an interface like SolrJ, just asynchronously / non-blocking
(built on top of [async-http-client](https://github.com/AsyncHttpClient/async-http-client) / [netty](https://github.com/netty/netty)).
For java it supports `CompletableFuture`, for scala you can choose between twitter's `Future` or the standard/SDK `Future`.

## Documentation

The documentation is available at [http://inoio.github.io/solrs/](http://inoio.github.io/solrs/)

## License

This software is licensed under the Apache 2 license, see LICENSE.txt.
