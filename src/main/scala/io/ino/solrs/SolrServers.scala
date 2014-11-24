package io.ino.solrs

import java.util.concurrent.{Executors, ScheduledExecutorService, ThreadFactory}

import com.ning.http.client.{Response, AsyncCompletionHandler, AsyncHttpClient}
import org.apache.solr.client.solrj.{SolrServerException, SolrQuery}
import org.apache.solr.common.cloud._
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.util.control.NonFatal

/**
 * Provides the list of solr servers.
 */
trait SolrServers {
  /**
   * The currently known solr servers.
   */
  def all: Seq[SolrServer]

  /**
   * Determines Solr servers matching the given solr query (e.g. based on the "collection" param).
   */
  def matching(q: SolrQuery): IndexedSeq[SolrServer]
}

class StaticSolrServers(override val all: IndexedSeq[SolrServer]) extends SolrServers {
  override def matching(q: SolrQuery): IndexedSeq[SolrServer] = all
}
object StaticSolrServers {
  def apply(baseUrls: IndexedSeq[String]): StaticSolrServers = new StaticSolrServers(baseUrls.map(SolrServer(_)))
}

import scala.concurrent.duration._
import scala.languageFeature.postfixOps
import java.util.concurrent.TimeUnit

private object ZkClusterStateUpdateTF {
  private val tg = new ThreadGroup("solrs-CloudSolrServersUpdate")
}

private class ZkClusterStateUpdateTF extends ThreadFactory {
  import ZkClusterStateUpdateTF._
  override def newThread(r: Runnable): Thread = {
    val td: Thread = new Thread(tg, r, "solrs-CloudSolrServersUpdateThread-" + tg.activeCount() + 1)
    td.setDaemon(true)
    return td
  }
}

/**
 * Provides servers based on information from from ZooKeeper. Uses the ZkStateReader to read the ZK cluster state,
 * which is also used by solrj's CloudSolrServer. While ZkStateReader uses ZK Watches to get cluster state changes
 * from ZK, we're regularly updating our internal state by reading the cluster state from ZkStateReader.
 *
 * @param zkHost The zkHost string, in $host:$port format, multiple hosts are specified comma separated
 * @param zkClientTimeout The zk session timeout (passed to ZkStateReader)
 * @param zkConnectTimeout The zk connection timeout (passed to ZkStateReader),
 *                         also used for ZkStateReader initialization attempt interval.
 *                         Note that we're NOT stopping connection retries after connect timeout!
 * @param clusterStateUpdateInterval Used for pulling the ClusterState from ZkStateReader
 * @param defaultCollection Optional default collection to use when the query does not specify the "collection" param.
 */
class CloudSolrServers(zkHost: String,
                       zkClientTimeout: Duration = 15 seconds, // default from Solr Core, see also SOLR-5221
                       zkConnectTimeout: Duration = 10 seconds, // default from solrj CloudSolrServer
                       clusterStateUpdateInterval: Duration = 1 second,
                       defaultCollection: Option[String] = None) extends SolrServers {

  private val logger = LoggerFactory.getLogger(getClass())

  private var maybeZk: Option[ZkStateReader] = None

  private var lastClusterState: ClusterState = _
  private var collectionToServers = Map.empty[String, IndexedSeq[SolrServer]]

  private var scheduledExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(1, new ZkClusterStateUpdateTF)

  createZkStateReader()

  private def createZkStateReader(): Unit = {
    // Setup ZkStateReader, schedule retry if ZK is unavailable
    try {
      // Creating ZkStateReader can fail when ZK is not available
      maybeZk = Some(new ZkStateReader(zkHost, zkClientTimeout.toMillis.toInt, zkConnectTimeout.toMillis.toInt))
      logger.info(s"Connected to zookeeper at $zkHost")
      maybeZk.foreach(initZkStateReader)
    } catch {
      case NonFatal(e) =>
        logger.warn(s"Could not connect to ZK, seems to be unavailable. Retrying in $zkConnectTimeout. Original exception: $e")
        scheduledExecutor.schedule(new Runnable {
          override def run(): Unit = createZkStateReader()
        }, zkConnectTimeout.toMillis, TimeUnit.MILLISECONDS)
    }
  }

  private def initZkStateReader(zkStateReader: ZkStateReader): Unit = {
    try {
      // createClusterStateWatchersAndUpdate fails when no solr servers are connected to solr, exception is:
      // KeeperException$NoNodeException: KeeperErrorCode = NoNode for /live_nodes
      zkStateReader.createClusterStateWatchersAndUpdate
      logger.info(s"Successfully created ZK cluster state watchers at $zkHost")

      // Now regularly update the server list from ZkStateReader clusterState
      scheduledExecutor.scheduleAtFixedRate(new Runnable {
        override def run(): Unit = updateFromClusterState(zkStateReader)
      }, 0, clusterStateUpdateInterval.toMillis, TimeUnit.MILLISECONDS)
    } catch {
      case NonFatal(e) =>
        logger.warn("Could not initialize ZkStateReader, this can happen when there are no solr servers connected." +
          s" Retrying in $zkConnectTimeout. Original exception: $e")
        scheduledExecutor.schedule(new Runnable {
          override def run(): Unit = initZkStateReader(zkStateReader)
        }, zkConnectTimeout.toMillis, TimeUnit.MILLISECONDS)
    }
  }

  /**
   * Updates the server list when the ZkStateReader clusterState changed
   */
  private def updateFromClusterState(zkStateReader: ZkStateReader): Unit = {
    val clusterState = zkStateReader.getClusterState
    if(clusterState != lastClusterState) {

      try {
        import scala.collection.JavaConversions._

        val newCollectionToServers = (for {
          collectionName <- clusterState.getCollections
          collectionSlice = clusterState.getCollection(collectionName).getSlices
          replica <- collectionSlice.map(_.getReplicas)
        } yield collectionName -> replica.map( repl =>
            SolrServer(ZkCoreNodeProps.getCoreUrl(repl), serverStatus(repl))
          ).toIndexedSeq
        ).toMap

        lastClusterState = clusterState

        if(newCollectionToServers != collectionToServers) {
          collectionToServers = newCollectionToServers
          if(logger.isDebugEnabled) logger.debug(s"Updated server map: $collectionToServers from ClusterState $clusterState")
          else logger.info(s"Updated server map: $collectionToServers")
        }

      } catch {
        case NonFatal(e) =>
          logger.error(s"Could not process cluster state, server list might get outdated. Cluster state: ${clusterState}", e)
      }
    }
  }

  def shutdown = {
    maybeZk.foreach(_.close())
    scheduledExecutor.shutdownNow()
  }

  private def serverStatus(replica: Replica): ServerStatus = replica.get(ZkStateReader.STATE_PROP) match {
    case ZkStateReader.ACTIVE => Enabled
    case ZkStateReader.RECOVERING => Disabled
    case ZkStateReader.RECOVERY_FAILED => Failed
    case ZkStateReader.DOWN => Failed
    case default =>
      // E.g. there's SYNC, which *should* not be used according to http://markmail.org/message/wt54x2xisileyeoo, but
      // we should at log unknown states and handle them as Failed.
      logger.warn(s"Unknown state $default, translating to 'Failed' for now...")
      Failed
  }

  /**
   * The currently known solr servers.
   */
  @volatile
  override def all: IndexedSeq[SolrServer] = collectionToServers.values.flatten.toSet.toIndexedSeq

  /**
   * An infinite iterator over known solr servers. When the last item is reached,
   * it should start from the first one again. When the known solr servers change,
   * the iterator must reflect this.
   */
  override def matching(q: SolrQuery): IndexedSeq[SolrServer] = {
    val collection = Option(q.get("collection")).orElse(defaultCollection).getOrElse(
      throw new SolrServerException("No collection param specified on request and no default collection has been set.")
    )
    collectionToServers.get(collection).getOrElse(Vector.empty)
  }

}

class ReloadingSolrServers(url: String, extractor: Array[Byte] => IndexedSeq[SolrServer], httpClient: AsyncHttpClient) extends SolrServers {

  private val logger = LoggerFactory.getLogger(getClass())

  private var solrServers = IndexedSeq.empty[SolrServer]

  /**
   * The currently known solr servers.
   */
  override def all: IndexedSeq[SolrServer] = solrServers

  /**
   * An infinite iterator over known solr servers. When the last item is reached,
   * it should start from the first one again. When the known solr servers change,
   * the iterator must reflect this.
   */
  override def matching(q: SolrQuery): IndexedSeq[SolrServer] = solrServers

  def reload(): Future[IndexedSeq[SolrServer]] = {
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