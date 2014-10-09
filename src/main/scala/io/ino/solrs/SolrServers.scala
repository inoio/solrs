package io.ino.solrs

import java.net.URL

import com.ning.http.client.{Response, AsyncCompletionHandler, AsyncHttpClient}
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.util.Try

/**
 * Provides the list of solr servers.
 */
trait SolrServers {
  /**
   * The currently known solr servers.
   */
  def all: Seq[SolrServer]

  /**
   * The number of currently known solr servers.
   */
  def length: Int = all.length

  /**
   * An infinite iterator over known solr servers. When the last item is reached,
   * it should start from the first one again. When the known solr servers change,
   * the iterator must reflect this.
   */
  def iterator: Iterator[SolrServer]
}

class StaticSolrServers(override val all: Seq[SolrServer]) extends SolrServers {
  override def iterator: Iterator[SolrServer] = Stream.continually(all).flatten.iterator
}
object StaticSolrServers {
  def apply(baseUrls: Seq[String]): StaticSolrServers = new StaticSolrServers(baseUrls.map(SolrServer(_)))
}

class ReloadingSolrServers(url: String, extractor: Array[Byte] => Seq[SolrServer], httpClient: AsyncHttpClient) extends SolrServers {

  private val logger = LoggerFactory.getLogger(getClass())

  private var solrServers = Seq.empty[SolrServer]

  /**
   * The currently known solr servers.
   */
  override def all: Seq[SolrServer] = solrServers

  /**
   * An infinite iterator over known solr servers. When the last item is reached,
   * it should start from the first one again. When the known solr servers change,
   * the iterator must reflect this.
   */
  override def iterator: Iterator[SolrServer] = new Iterator[SolrServer] {

    private var idx = 0

    override def hasNext: Boolean = solrServers.length > 0

    override def next(): SolrServer = {
      idx = if(idx < solrServers.length - 1) idx + 1 else 0
      // TODO: race condition, solrServers might be changed right now, so perhaps we
      // just should handle IndexOutOfBoundsException (instead of locking)
      solrServers(idx)
    }
  }

  def reload(): Future[Seq[SolrServer]] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    loadUrl().map { data =>
      // TODO: check if solr servers actually changed, perhaps only add/remove changed stuff
      // or somehow preserve the status of servers
      val oldServers = solrServers
      solrServers = extractor(data)
      logger.info(s"Changed solr servers from $oldServers to $solrServers")
      solrServers
    }
  }

  protected def loadUrl(): Future[Array[Byte]] = {
    val promise = scala.concurrent.promise[Array[Byte]]
    httpClient.prepareGet(url).execute(new AsyncCompletionHandler[Response]() {
      override def onCompleted(response: Response): Response = {
        promise.success(response.getResponseBodyAsBytes)
        response
      }
      override def onThrowable(t: Throwable) {
        logger.error("Could not load solr server list.", t)
        promise.failure(t)
      }
    })
    promise.future
  }


}