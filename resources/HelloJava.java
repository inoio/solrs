import io.ino.solrs.JavaAsyncSolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.response.QueryResponse;
import java.util.concurrent.CompletionStage;

class HelloJava {

  JavaAsyncSolrClient solr = JavaAsyncSolrClient.create("http://localhost:8983/solr/collection1");
  CompletionStage<QueryResponse> response = solr.query(new SolrQuery("java"));
  response.thenAccept(r -> System.out.println("found " + r.getResults().getNumFound() + " docs"));

  // At EOL...
  solr.shutdown();

}