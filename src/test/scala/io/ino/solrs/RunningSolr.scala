package io.ino.solrs

import org.apache.solr.client.solrj.impl.HttpSolrClient
import org.scalatest.BeforeAndAfterAll
import org.scalatest.ConfigMap
import org.scalatest.Suite
import org.scalatest.mock.MockitoSugar

trait RunningSolr extends BeforeAndAfterAll with MockitoSugar {
  this: Suite =>

  protected var solrRunner: SolrRunner = _
  protected var solr: HttpSolrClient = _

  override def beforeAll(configMap: ConfigMap) {
    solrRunner = SolrRunner.startOnce(8888)

    solr = new HttpSolrClient.Builder("http://localhost:" + solrRunner.port + "/solr/collection1").build()
  }

  override def afterAll(configMap: ConfigMap) {
    solr.close()
  }

}