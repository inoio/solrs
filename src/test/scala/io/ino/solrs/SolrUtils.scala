package io.ino.solrs

import java.net.{NetworkInterface, InetAddress}

import scala.jdk.CollectionConverters._

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
    resp.getResults.asScala.toList.map(_.getFieldValue("id").toString)
  }

  /** Determines the local hostname as the ZkController does it for SolrCloud nodes.
    */
  val hostName: String = {
    // See also https://svn.apache.org/repos/asf/lucene/dev/trunk/solr/core/src/java/org/apache/solr/cloud/ZkController.java
    // It picks InetAddress.getLocalHost if != loopback, or the last siteLocalAddress
    if(InetAddress.getLocalHost.getHostAddress != "127.0.0.1") {
      InetAddress.getLocalHost.getHostAddress
    }
    else {
      val siteLocalAddresses = for {
        iface <- NetworkInterface.getNetworkInterfaces.asScala.toList
        address <- iface.getInetAddresses.asScala if address.isSiteLocalAddress
      } yield address
      if(siteLocalAddresses.isEmpty) "127.0.0.1" else siteLocalAddresses.last.getHostAddress
    }
  }

}

object SolrUtils extends SolrUtils {

  val manyDocs: List[SolrInputDocument] = (1 to 1000).map(i => newInputDoc(s"id$i", s"doc$i", s"cat${i % 100}", i % 50)).toList
  val manyDocsIds: List[String] = manyDocs.map(_.getFieldValue("id").toString)
  val manyDocsAsJList: java.util.List[SolrInputDocument] = manyDocs.asJava

  val someDocs: List[SolrInputDocument] = manyDocs.take(50)
  val someDocsIds: List[String] = someDocs.map(_.getFieldValue("id").toString)
  val someDocsAsJList: java.util.List[SolrInputDocument] = someDocs.asJava

}