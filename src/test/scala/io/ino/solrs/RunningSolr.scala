package io.ino.solrs

import org.apache.solr.client.solrj.impl.Http2SolrClient
import org.scalatest.{BeforeAndAfterAll, Suite}

trait RunningSolr extends BeforeAndAfterAll {
  this: Suite =>

  protected var solrRunner: SolrRunner = _
  protected var solrJClient: Http2SolrClient = _

  override def beforeAll(): Unit = {
    solrRunner = SolrRunner.startOnce(8888)

    solrJClient = new Http2SolrClient.Builder("http://localhost:" + solrRunner.port + "/solr/collection1").build()
  }

  override def afterAll(): Unit = {
    solrJClient.close()
  }

}
