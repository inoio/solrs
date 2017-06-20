package io.ino.solrs

import java.util.Arrays.asList

import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.SolrRequest.METHOD.POST
import org.apache.solr.client.solrj.beans.Field
import org.scalatest.concurrent.Eventually.eventually
import org.scalatest.concurrent.PatienceConfiguration.Timeout

import scala.annotation.meta.field
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.language.postfixOps

class AsyncSolrClientFunSpec extends StandardFunSpec with RunningSolr {

  private implicit val timeout = 1.second

  private lazy val solrs = AsyncSolrClient(s"http://localhost:${solrRunner.port}/solr/collection1")

  import io.ino.solrs.SolrUtils._

  override def beforeEach() {
    eventually(Timeout(10 seconds)) {
      solr.deleteByQuery("*:*")
    }
  }

  override def afterAll(): Unit = {
    super.afterAll()
    solrs.shutdown()
  }

  describe("Solr") {

    it("should add docs as iterable") {
      val doc1 = newInputDoc("id1", "doc1", "cat1", 10)
      val doc2 = newInputDoc("id2", "doc2", "cat1", 20)
      await(solrs.addDocs(docs = Iterable(doc1, doc2)))
      solr.commit()
      val docs = solr.query(new SolrQuery("*:*")).getResults
      docs.getNumFound should be (2)
      docs.asScala.map(_.getFieldValue("price")) should contain theSameElementsAs List(10, 20)
    }

    it("should add docs as iterator") {
      val doc1 = newInputDoc("id1", "doc1", "cat1", 10)
      val doc2 = newInputDoc("id2", "doc2", "cat1", 20)
      await(solrs.addDocs(Iterator(doc1, doc2)))
      solr.commit()
      val docs = solr.query(new SolrQuery("*:*")).getResults
      docs.getNumFound should be (2)
      docs.asScala.map(_.getFieldValue("price")) should contain theSameElementsAs List(10, 20)
    }

    it("should add doc") {
      await(solrs.addDoc(doc = newInputDoc("id1", "doc1", "cat1", 10)))
      solr.commit()
      val docs = solr.query(new SolrQuery("*:*")).getResults
      docs.getNumFound should be (1)
      docs.asScala.map(_.getFieldValue("price")) should contain theSameElementsAs List(10)
    }

    it("should add bean") {
      val bean = TestBean("id1", "doc1", "cat1", 10)
      await(solrs.addBean(obj = bean))
      solr.commit()
      val response = solr.query(new SolrQuery("*:*"))
      response.getResults.getNumFound should be (1)
      response.getBeans(classOf[TestBean]) should contain theSameElementsAs List(bean)
    }

    it("should add beans as iterable") {
      val bean1 = TestBean("id1", "doc1", "cat1", 10)
      val bean2 = TestBean("id2", "doc2", "cat1", 20)
      await(solrs.addBeans(beans = Iterable(bean1, bean2)))
      solr.commit()
      val response = solr.query(new SolrQuery("*:*"))
      response.getResults.getNumFound should be (2)
      response.getBeans(classOf[TestBean]).asScala should contain theSameElementsAs List(bean1, bean2)
    }

    it("should add beans as iterator") {
      val bean1 = TestBean("id1", "doc1", "cat1", 10)
      val bean2 = TestBean("id2", "doc2", "cat1", 20)
      await(solrs.addBeans(beanIterator = Iterator(bean1, bean2)))
      solr.commit()
      val response = solr.query(new SolrQuery("*:*"))
      response.getResults.getNumFound should be (2)
      response.getBeans(classOf[TestBean]).asScala should contain theSameElementsAs List(bean1, bean2)
    }

    it("should commit") {
      solr.add(newInputDoc("id1", "doc1", "cat1", 10))
      await(solrs.commit())
      val docs = solr.query(new SolrQuery("*:*")).getResults
      docs.getNumFound should be (1)
      docs.asScala.map(_.getFieldValue("price")) should contain theSameElementsAs List(10)
    }

    it("should delete by id") {
      val doc1 = newInputDoc("id1", "doc1", "cat1", 10)
      val doc2 = newInputDoc("id2", "doc2", "cat1", 20)
      solr.add(asList(doc1, doc2))
      solr.commit()
      await(solrs.deleteById(id = "id1"))
      solr.commit()
      val docs = solr.query(new SolrQuery("*:*")).getResults
      docs.getNumFound should be (1)
      docs.asScala.map(_.getFieldValue("price")) should contain theSameElementsAs List(20)
    }

    it("should delete by ids") {
      val doc1 = newInputDoc("id1", "doc1", "cat1", 10)
      val doc2 = newInputDoc("id2", "doc2", "cat1", 20)
      val doc3 = newInputDoc("id3", "doc3", "cat2", 30)
      solr.add(asList(doc1, doc2, doc3))
      solr.commit()
      await(solrs.deleteByIds(ids = Seq("id1", "id2")))
      solr.commit()
      val docs = solr.query(new SolrQuery("*:*")).getResults
      docs.getNumFound should be (1)
      docs.asScala.map(_.getFieldValue("price")) should contain theSameElementsAs List(30)
    }

    it("should delete by query") {
      val doc1 = newInputDoc("id1", "doc1", "cat1", 10)
      val doc2 = newInputDoc("id2", "doc2", "cat1", 20)
      val doc3 = newInputDoc("id3", "doc3", "cat2", 30)
      solr.add(asList(doc1, doc2, doc3))
      solr.commit()
      await(solrs.deleteByQuery(query = "cat:cat1"))
      solr.commit()
      val docs = solr.query(new SolrQuery("*:*")).getResults
      docs.getNumFound should be (1)
      docs.asScala.map(_.getFieldValue("price")) should contain theSameElementsAs List(30)
    }

    it("should query") {
      val doc1 = newInputDoc("id1", "doc1", "cat1", 10)
      val doc2 = newInputDoc("id2", "doc2", "cat1", 20)
      val doc3 = newInputDoc("id3", "doc3", "cat2", 30)
      solr.add(asList(doc1, doc2, doc3))
      solr.commit()
      val docs = await(solrs.query(new SolrQuery("cat:cat1"))).getResults
      docs.getNumFound should be (2)
      docs.asScala.map(_.getFieldValue("price")) should contain theSameElementsAs List(10, 20)
    }

    it("should get by id") {
      val doc1 = newInputDoc("id1", "doc1", "cat1", 10)
      val doc2 = newInputDoc("id2", "doc2", "cat1", 20)
      solr.add(asList(doc1, doc2))
      solr.commit()
      await(solrs.getById(id = "id1")).map(_.getFieldValue("price")) should be (Some(10))
    }

    it("should get by id absent") {
      solr.add(newInputDoc("id1", "doc1", "cat1", 10))
      solr.commit()
      await(solrs.getById(id = "id2")) should be (empty)
    }

    it("should get by ids") {
      val doc1 = newInputDoc("id1", "doc1", "cat1", 10)
      val doc2 = newInputDoc("id2", "doc2", "cat1", 20)
      val doc3 = newInputDoc("id3", "doc3", "cat2", 30)
      solr.add(asList(doc1, doc2, doc3))
      solr.commit()
      val docs = await(solrs.getByIds(ids = Iterable("id1", "id2")))
      docs.getNumFound should be (2)
      docs.asScala.map(_.getFieldValue("price")) should contain theSameElementsAs List(10, 20)
    }

    it("should get by ids absent") {
      solr.add(newInputDoc("id1", "doc1", "cat1", 10))
      solr.commit()
      val docs = await(solrs.getByIds(ids = Iterable("id2", "id3")))
      docs.getNumFound should be (0)
      docs.asScala should be (empty)
    }

    it("should pass same query parameters") {
      // POST method should pass parameters exactly once, not twice.
      // check this via response header's parameter echo (params field)
      val query = new SolrQuery("cat:cat1")
      val paramEchoExpected = {
        solr.query(query, POST).getHeader.get("params")
      }
      val paramEcho = await(solrs.query(query, POST)).getHeader.get("params")
      paramEcho should be (paramEchoExpected)
    }

    it("should pass same query parameters in post and get") {
      // Post query and Get query should pass exactly the same set of parameters to Solr.
      val query = new SolrQuery("cat:cat1")
      val paramEchoGet = await(solrs.query(query)).getHeader.get("params")
      val paramEchoPost = await(solrs.query(query, POST)).getHeader.get("params")
      paramEchoPost should be (paramEchoGet)
    }
  }

}

case class TestBean(@(Field @field) id: String,
                    @(Field @field) name: String,
                    @(Field @field) category: String,
                    @(Field @field) price: Float) {
  def this() = this(null, null, null, 0)
}
