import io.ino.solrs._
import io.ino.solrs.future.ScalaFutureFactory.Implicit

class RequestInterception extends App {

  // #intro
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
    .withRequestInterceptor(loggingInterceptor)
    .build
  // #intro


}