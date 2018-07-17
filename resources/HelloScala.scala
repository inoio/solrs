import io.ino.solrs.AsyncSolrClient
import io.ino.solrs.future.ScalaFutureFactory.Implicit
import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.response.QueryResponse
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

class HelloScala extends App {

  val solr = AsyncSolrClient("http://localhost:8983/solr/collection1")
  val response: Future[QueryResponse] = solr.query(new SolrQuery("scala"))
  response.foreach {
    qr => println(s"found ${qr.getResults.getNumFound} docs")
  }

  // Don't forget...
  solr.shutdown()

}