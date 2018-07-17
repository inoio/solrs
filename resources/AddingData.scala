import io.ino.solrs.AsyncSolrClient
import io.ino.solrs.future.ScalaFutureFactory.Implicit
import org.apache.solr.common.SolrInputDocument

// #annotations
import org.apache.solr.client.solrj.beans.Field
import scala.annotation.meta.field

// #annotations

// #annotations
case class Item(@(Field @field) id: String,
                @(Field @field) name: String,
                @(Field @field)("cat") category: String,
                @(Field @field) price: Float) {
  def this() = this(null, null, null, 0)
}

// #annotations

class AddingData extends App {

  // #adding_data
  val solr = AsyncSolrClient("http://localhost:8983/solr/collection1")

  val doc1 = new SolrInputDocument()
  doc1.addField("id", "id1")
  doc1.addField("name", "doc1")
  doc1.addField("cat", "cat1")
  doc1.addField("price", 10)

  val doc2 = new SolrInputDocument()
  doc2.addField("id", "id2")
  doc2.addField("name", "doc2")
  doc2.addField("cat", "cat1")
  doc2.addField("price", 20)

  for {
    _ <- solr.addDocs(docs = Iterable(doc1, doc2))
    _ <- solr.commit()
  } yield print("docs added")
  // #adding_data

  // #annotations
  val solr = AsyncSolrClient("http://localhost:8983/solr/collection1")

  val item1 = Item("id1", "doc1", "cat1", 10)
  val item2 = Item("id2", "doc2", "cat1", 20)

  for {
    _ <- solr.addBeans(beans = Iterable(item1, item2))
    _ <- solr.commit()
  } yield print("beans added")
  // #annotations

}