import io.ino.solrs.AsyncSolrClient
import io.ino.solrs.future.ScalaFutureFactory.Implicit
import org.apache.solr.client.solrj.impl.XMLResponseParser
import org.asynchttpclient.DefaultAsyncHttpClient

class HelloScala extends App {

  val solr = AsyncSolrClient.Builder("http://localhost:8983/solr/collection1")
    .withHttpClient(new DefaultAsyncHttpClient())
    .withResponseParser(new XMLResponseParser())
    .build

  // ...

}