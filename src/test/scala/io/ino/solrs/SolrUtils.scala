package io.ino.solrs

import java.net.{NetworkInterface, InetAddress}

import scala.collection.JavaConversions._

import org.apache.solr.client.solrj.response.QueryResponse
import org.apache.solr.common.SolrInputDocument

trait SolrUtils {

  def newInputDoc(id: String, name: String, category: String, price: Float): SolrInputDocument = {
    val doc = new SolrInputDocument()
    doc.addField("id", id)
    doc.addField("name", name)
    doc.addField("cat", category)
    doc.addField("price", price)
    doc
  }

  def getIds(resp: QueryResponse): List[String] = {
    resp.getResults.toList.map(_.getFieldValue("id").toString)
  }

  /** Determines the local hostname as the ZkController does it for SolrCloud nodes.
    */
  val hostName = {
    // See also https://svn.apache.org/repos/asf/lucene/dev/trunk/solr/core/src/java/org/apache/solr/cloud/ZkController.java
    // It picks InetAddress.getLocalHost if != loopback, or the last siteLocalAddress
    import scala.collection.JavaConversions._
    if(InetAddress.getLocalHost.getHostAddress != "127.0.0.1") {
      InetAddress.getLocalHost.getHostAddress
    }
    else {
      val siteLocalAddresses = for {
        iface <- NetworkInterface.getNetworkInterfaces().toList
        address <- iface.getInetAddresses if address.isSiteLocalAddress
      } yield address
      if(siteLocalAddresses.isEmpty) "127.0.0.1" else siteLocalAddresses.last.getHostAddress
    }
  }

}

object SolrUtils extends SolrUtils {

  val manyDocs = (1 to 1000).map(i => newInputDoc(s"id$i", s"doc$i", s"cat${i % 100}", i % 50)).toList
  val manyDocsIds = manyDocs.map(_.getFieldValue("id").toString)
  val manyDocsAsJList: java.util.List[SolrInputDocument] = manyDocs

  val someDocs = manyDocs.take(50)
  val someDocsIds = someDocs.map(_.getFieldValue("id").toString)
  val someDocsAsJList: java.util.List[SolrInputDocument] = someDocs

}