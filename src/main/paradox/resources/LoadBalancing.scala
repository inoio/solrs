// #fastest_server
import io.ino.solrs._
import io.ino.solrs.future.ScalaFutureFactory.Implicit
import scala.concurrent.duration._

// #fastest_server

class RoundRobin extends App {

  // #round_robin
  val lb = RoundRobinLB(IndexedSeq(
    "http://localhost:8983/solr/collection1",
    "http://localhost:8984/solr/collection1"
  ))
  val solr = AsyncSolrClient.Builder(lb).build
  // #round_robin

  // #fastest_server
  val lb = {
    val servers = StaticSolrServers(IndexedSeq(
      "http://localhost:8983/solr/collection1",
      "http://localhost:8984/solr/collection1"
    ))
    val col1TestQuery = "collection1" -> new SolrQuery("*:*").setRows(0)
    def collectionAndTestQuery(server: SolrServer) = col1TestQuery
    new FastestServerLB(servers, collectionAndTestQuery, minDelay = 50 millis, maxDelay = 5 seconds, initialTestRuns = 50)
  }
  val solr = AsyncSolrClient.Builder(lb).build
  // #fastest_server

}