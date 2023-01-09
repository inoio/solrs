package io.ino.solrs.usage

import org.slf4j.LoggerFactory
import scala.concurrent.Future

class UsageScala1 {

  {
    import io.ino.solrs.AsyncSolrClient
    import io.ino.solrs.future.ScalaFutureFactory.Implicit
    import org.apache.solr.client.solrj.SolrQuery
    import org.apache.solr.client.solrj.response.QueryResponse
    import scala.concurrent.ExecutionContext.Implicits.global
    import scala.concurrent.Future

    val solr = AsyncSolrClient("http://localhost:8983/solr")
    val response: Future[QueryResponse] = solr.query(new SolrQuery("scala"))
    response.foreach {
      qr => println(s"found ${qr.getResults.getNumFound} docs")
    }

    // Just included to present the 'shutdown'...
    solr.shutdown()
  }

  {
    import io.ino.solrs._
    import io.ino.solrs.future.Future
    import io.ino.solrs.future.ScalaFutureFactory.Implicit
    import org.apache.solr.client.solrj.response.SolrResponseBase
    import org.apache.solr.client.solrj.SolrRequest
    import org.apache.solr.client.solrj.SolrResponse

    val logger = LoggerFactory.getLogger(getClass)

    val loggingInterceptor = new RequestInterceptor {
      override def interceptRequest[T <: SolrResponse](f: (SolrServer, SolrRequest[_ <: T]) => Future[T])
                                                      (solrServer: SolrServer, r: SolrRequest[_ <: T]): Future[T] = {
        val start = System.currentTimeMillis()
        f(solrServer, r).map { qr =>
          val requestTime = System.currentTimeMillis() - start
          logger.info(s"Request $r to $solrServer took $requestTime ms (query time in solr: ${qr.asInstanceOf[SolrResponseBase].getQTime} ms).")
          qr
        }
      }
    }

    val solr = AsyncSolrClient.Builder("http://localhost:8983/solr/collection1")
      .withRequestInterceptor(loggingInterceptor).build
  }

}

class UsageScalaTwitter1 {

  import io.ino.solrs.AsyncSolrClient
  import io.ino.solrs.future.TwitterFutureFactory.Implicit
  import org.apache.solr.client.solrj.SolrQuery
  import org.apache.solr.client.solrj.response.QueryResponse
  import com.twitter.util.Future

  val solr: AsyncSolrClient[Future] = AsyncSolrClient("http://localhost:8983/solr")
  val response: Future[QueryResponse] = solr.query(new SolrQuery("scala"))
  response.onSuccess {
    qr => println(s"found ${qr.getResults.getNumFound} docs")
  }

  // Just included to present the 'shutdown'...
  solr.shutdown()

}

class UsageScala2 {
  import io.ino.solrs.AsyncSolrClient
  import io.ino.solrs.future.ScalaFutureFactory.Implicit
  import org.apache.solr.client.solrj.impl.XMLResponseParser
  import org.asynchttpclient.DefaultAsyncHttpClient

  val solr: AsyncSolrClient[Future] = AsyncSolrClient.Builder("http://localhost:8983/solr")
    .withHttpClient(new DefaultAsyncHttpClient())
    .withResponseParser(new XMLResponseParser())
    .build
}