package io.ino.solrs

import org.scalatest.BeforeAndAfterAll
import org.scalatest.ConfigMap
import org.scalatest.Suite
import org.scalatest.mock.MockitoSugar
import org.apache.solr.client.solrj.impl.HttpSolrServer
import org.apache.solr.client.solrj.SolrServer

trait RunningSolr extends BeforeAndAfterAll with MockitoSugar {
  this: Suite =>

  protected var solrRunner: SolrRunner = _
  protected var solr: HttpSolrServer = _

  override def beforeAll(configMap: ConfigMap) {
    solrRunner = SolrRunner.startOnce(8888)

    solr = new HttpSolrServer("http://localhost:" + solrRunner.port + "/solr/")
  }

  override def afterAll(configMap: ConfigMap) {
    solr.shutdown()
  }

}