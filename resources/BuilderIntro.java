import io.ino.solrs.JavaAsyncSolrClient;
import org.apache.solr.client.solrj.impl.XMLResponseParser;
import org.asynchttpclient.DefaultAsyncHttpClient;

class HelloJava {

  JavaAsyncSolrClient solr = JavaAsyncSolrClient.builder("http://localhost:8983/solr/collection1")
    .withHttpClient(new DefaultAsyncHttpClient())
    .withResponseParser(new XMLResponseParser())
    .build();

  // ...

}