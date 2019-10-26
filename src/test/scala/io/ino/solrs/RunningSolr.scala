package io.ino.solrs

import org.apache.solr.client.solrj.impl.HttpSolrClient
import org.scalatest.{BeforeAndAfterAll, ConfigMap, Suite}

trait RunningSolr extends BeforeAndAfterAll {
  this: Suite =>

  protected var solrRunner: SolrRunner = _
  protected var solrJClient: HttpSolrClient = _

  override def beforeAll(): Unit = {
    solrRunner = SolrRunner.startOnce(8888)

    solrJClient = new HttpSolrClient.Builder("http://localhost:" + solrRunner.port + "/solr/collection1").build()
  }

  override def afterAll(): Unit = {
    solrJClient.close()
  }

}
