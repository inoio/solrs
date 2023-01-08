import io.ino.solrs.AsyncSolrClient
import io.ino.solrs.future.TwitterFutureFactory.Implicit
import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.response.QueryResponse
import com.twitter.util.Future

class HelloTwitter extends App {

  val solr: AsyncSolrClient[Future] = AsyncSolrClient("http://localhost:8983/solr")
  val response: Future[QueryResponse] = solr.query(new SolrQuery("scala"))
  response.onSuccess {
    qr => println(s"found ${qr.getResults.getNumFound} docs")
  }

  // Finally...
  solr.shutdown()

}