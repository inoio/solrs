package io.ino.solrs

/**
 * Represents a solr host.
 */
case class SolrServer(val baseUrl: String) {

  @volatile
  var status: ServerStatus = Enabled

}

sealed trait ServerStatus
case object Enabled extends ServerStatus
case object Disabled extends ServerStatus
case object Failed extends ServerStatus