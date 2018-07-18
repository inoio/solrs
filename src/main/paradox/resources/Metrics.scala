import io.ino.solrs._
import io.ino.solrs.future.ScalaFutureFactory.Implicit

class Metrics extends App {

  // #intro
  val solr = AsyncSolrClient.Builder("http://localhost:8983/solr/collection1")
    .withMetrics(new CodaHaleMetrics())
    .build
  // #intro


}