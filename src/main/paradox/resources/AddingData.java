import static java.util.Arrays.asList;
import io.ino.solrs.JavaAsyncSolrClient;
// #annotations
import org.apache.solr.client.solrj.beans.Field;

// #annotations
import org.apache.solr.common.SolrInputDocument;

// #annotations
class Item {
	@Field
	String id;

	@Field
	String name;

	@Field("cat")
	String category;

	@Field
	float price;

	TestBean(String id, String name, String category, float price) {
		this.id = id;
		this.name = name;
		this.category = category;
		this.price = price;
	}
}

// #annotations

class AddingData {

  // #adding_data
  JavaAsyncSolrClient solr = JavaAsyncSolrClient.create("http://localhost:8983/solr/collection1");

  SolrInputDocument doc1 = new SolrInputDocument();
  doc1.addField("id", "id1");
  doc1.addField("name", "doc1");
  doc1.addField("cat", "cat1");
  doc1.addField("price", 10);

  SolrInputDocument doc2 = new SolrInputDocument();
  doc2.addField("id", "id2");
  doc2.addField("name", "doc2");
  doc2.addField("cat", "cat1");
  doc2.addField("price", 20);

  solr.addDocs(asList(doc1, doc2))
    .thenCompose(r -> solr.commit())
    .thenAccept(r -> System.out.println("docs added"));
  // #adding_data

  // #annotations
  JavaAsyncSolrClient solr = JavaAsyncSolrClient.create("http://localhost:8983/solr/collection1");

  Item item1 = new Item("id1", "doc1", "cat1", 10);
  Item item2 = new Item("id2", "doc2", "cat1", 20);

  solr.addBeans(asList(item1, item2))
    .thenCompose(r -> solr.commit())
    .thenAccept(r -> System.out.println("beans added"));
  // #annotations
  
}