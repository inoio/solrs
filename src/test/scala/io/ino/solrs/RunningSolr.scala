package io.ino.solrs

import org.apache.solr.client.solrj.impl.HttpSolrClient
import org.scalatest.{BeforeAndAfterAll, ConfigMap, Suite}

trait RunningSolr extends BeforeAndAfterAll {
  this: Suite =>

  protected var solrRunner: SolrRunner = _
  protected var solr: HttpSolrClient = _

  override def beforeAll(configMap: ConfigMap) {
    solrRunner = SolrRunner.startOnce(8888)

    solr = new HttpSolrClient("http://localhost:" + solrRunner.port + "/solr/collection1")
  }

  override def afterAll(configMap: ConfigMap) {
    solr.close()
  }

}
