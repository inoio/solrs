import io.ino.solrs.*;
import java.util.Collections;
import static java.util.concurrent.TimeUnit.*;

class SolrCloud {

  // #intro
  CloudSolrServers<?> servers = CloudSolrServers.builder("localhost:2181").build();
  JavaAsyncSolrClient solr = JavaAsyncSolrClient.builder(new RoundRobinLB(servers)).build();
  // #intro

  // #extended
  CloudSolrServers<?> servers = CloudSolrServers.builder("host1:2181,host2:2181")
    .withZkClientTimeout(15, SECONDS)
    .withZkConnectTimeout(10, SECONDS)
    .withClusterStateUpdateInterval(1, SECONDS)
    .withDefaultCollection("collection1")
    .withWarmupQueries((collection) -> Collections.singletonList(new SolrQuery("*:*")), 10)
    .build();
  JavaAsyncSolrClient solr = JavaAsyncSolrClient.builder(new RoundRobinLB(servers)).build();
  // #extended

}