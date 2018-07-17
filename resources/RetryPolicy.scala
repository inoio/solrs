import io.ino.solrs._
import io.ino.solrs.future.ScalaFutureFactory.Implicit

class RetryPolicy extends App {

  // #intro
  val solr = AsyncSolrClient.Builder(RoundRobinLB(new CloudSolrServers("host1:2181,host2:2181")))
    .withRetryPolicy(RetryPolicy.TryAvailableServers)
    .build
  // #intro


}