import io.ino.solrs._
import io.ino.solrs.future.ScalaFutureFactory.Implicit

class SolrCloud extends App {

  // #intro
  val servers = new CloudSolrServers("localhost:2181")
  val solr = AsyncSolrClient.Builder(RoundRobinLB(servers)).build
  // #intro

  // #extended
  val servers = new CloudSolrServers(
    zkHost = "host1:2181,host2:2181",
    zkClientTimeout = 15 seconds,
    zkConnectTimeout = 10 seconds,
    clusterStateUpdateInterval = 1 second,
    defaultCollection = Some("collection1"),
    warmupQueries = WarmupQueries("collection1" => Seq(new SolrQuery("*:*")), count = 10))
  val solr = AsyncSolrClient.Builder(RoundRobinLB(servers)).build
  // #extended

}