// #fastest_server
import io.ino.solrs.*;
import static java.util.Arrays.asList;
import scala.Tuple2;
import static java.util.concurrent.TimeUnit.*;

// #fastest_server

class RoundRobin {

  // #round_robin
  RoundRobinLB lb = RoundRobinLB.create(asList(
    "http://localhost:8983/solr/collection1",
    "http://localhost:8984/solr/collection1"
  ));
  JavaAsyncSolrClient solr = JavaAsyncSolrClient.builder(lb).build();
  // #round_robin

  // #fastest_server
  StaticSolrServers servers = StaticSolrServers.create(Arrays.asList(
    "http://localhost:8983/solr/collection1",
    "http://localhost:8984/solr/collection1"
  ));
  Tuple2<String, SolrQuery> col1TestQuery = new Tuple2<>("collection1", new SolrQuery("*:*").setRows(0));
  Function<SolrServer, Tuple2<String, SolrQuery>> collectionAndTestQuery = server -> col1TestQuery;
  FastestServerLB<?> lb = FastestServerLB.builder(servers, collectionAndTestQuery)
          .withMinDelay(50, MILLISECONDS)
          .withMaxDelay(5, SECONDS)
          .withInitialTestRuns(50)
          .build();
  JavaAsyncSolrClient solr = JavaAsyncSolrClient.builder(lb).build();
  // #fastest_server

}