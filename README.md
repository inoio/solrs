# solrs - async solr client for scala

[![Build Status](https://travis-ci.org/inoio/solrs.png?branch=master)](https://travis-ci.org/inoio/solrs)

This is a solr client for scala providing a query interface like SolrJ, just asynchronously / non-blocking.

## Installation

You must add the library to the dependencies of the build file, e.g. add to `build.sbt`:

    libraryDependencies += "io.ino" %% "solrs" % "1.0.0-RC4"

solrs is published to maven central for both scala 2.10 and 2.11.

## Usage

At first an instance of `AsyncSolrClient` must be created with the url to the Solr server, an `AsyncHttpClient`
instance and the response parser to use.
This client can then be used to query solr and process future responses.

A complete example:

```scala
import com.ning.http.client.AsyncHttpClient
import io.ino.solrs.AsyncSolrClient
import org.apache.solr.client.solrj.response.QueryResponse
import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.impl.XMLResponseParser
import scala.concurrent.ExecutionContext.Implicits.global

val solr = new AsyncSolrClient("http://localhost:8983/solr",
      new AsyncHttpClient(), new XMLResponseParser())

val query = new SolrQuery("scala")
val response: Future[QueryResponse] = solr.query(query)

response.onSuccess {
  case qr => println(s"found ${qr.getResults.getNumFound} docs")
}
```

## License

The license is Apache 2.0, see LICENSE.txt.