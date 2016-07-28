package io.ino.solrs

import java.util.concurrent.{Executors, ScheduledExecutorService, ThreadFactory}

import com.ning.http.client.{AsyncCompletionHandler, AsyncHttpClient, Response}
import io.ino.solrs.CloudSolrServers.WarmupQueries
import io.ino.solrs.ServerStateChangeObservable.StateChange
import org.apache.solr.client.solrj.response.QueryResponse
import org.apache.solr.client.solrj.{SolrQuery, SolrServerException}
import org.apache.solr.common.cloud._
import org.slf4j.LoggerFactory

import scala.collection.immutable.Iterable
import scala.collection.mutable
import scala.concurrent.{Future, Promise}
import scala.language.postfixOps
import scala.language.postfixOps
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/**
 * Provides the list of solr servers.
 */
trait SolrServers extends AsyncSolrClientAware {
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

import java.util.concurrent.TimeUnit

import scala.concurrent.duration._
import scala.languageFeature.postfixOps

private object ZkClusterStateUpdateTF {
  private val tg = new ThreadGroup("solrs-CloudSolrServersUpdate")
}

private class ZkClusterStateUpdateTF extends ThreadFactory {
  import ZkClusterStateUpdateTF._
  override def newThread(r: Runnable): Thread = {
    val td: Thread = new Thread(tg, r, "solrs-CloudSolrServersUpdateThread-" + tg.activeCount() + 1)
    td.setDaemon(true)
    td
  }
}

trait StateChangeObserver {
  def onStateChange(event: StateChange)
}

trait ServerStateChangeObservable {
  def register(listener: StateChangeObserver)
}

object ServerStateChangeObservable {

  sealed trait StateChange
  case class Added(server: SolrServer, collection: String) extends StateChange
  case class Removed(server: SolrServer, collection: String) extends StateChange
  case class StateChanged(from: SolrServer, to: SolrServer, collection: String) extends StateChange

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
                       defaultCollection: Option[String] = None,
                       warmupQueries: Option[WarmupQueries] = None) extends SolrServers with ServerStateChangeObservable {

  import CloudSolrServers._

  private var maybeZk: Option[ZkStateReader] = None

  private var lastClusterState: ClusterState = _
  private var collectionToServers = Map.empty[String, IndexedSeq[SolrServer]].withDefaultValue(IndexedSeq.empty)

  private var scheduledExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(1, new ZkClusterStateUpdateTF)

  private var asyncSolrClient: AsyncSolrClient = _

  override def setAsyncSolrClient(client: AsyncSolrClient): Unit = {
    asyncSolrClient = client
    createZkStateReader()
  }

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
      zkStateReader.createClusterStateWatchersAndUpdate()
      logger.info(s"Successfully created ZK cluster state watchers at $zkHost")

      // Directly update, because scheduler thread creation might take too long (issue #7)
      updateFromClusterState(zkStateReader)
      // Now regularly update the server list from ZkStateReader clusterState
      scheduledExecutor.scheduleAtFixedRate(new Runnable {
        override def run(): Unit = updateFromClusterState(zkStateReader)
      }, clusterStateUpdateInterval.toMillis, clusterStateUpdateInterval.toMillis, TimeUnit.MILLISECONDS)
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
    import scala.concurrent.ExecutionContext.Implicits.global

    // could perhaps be replaced with zkStateReader.registerCollectionStateWatcher(collection, watcher);

    val clusterState = zkStateReader.getClusterState
    if(clusterState != lastClusterState) {

      try {

        // expect the new collection to servers map just for better readability on usage side...
        def set(newCollectionToServers: Map[String, IndexedSeq[SolrServer]]): Unit = {
          notifyObservers(collectionToServers, newCollectionToServers)
          collectionToServers = newCollectionToServers
          if (logger.isDebugEnabled) logger.debug (s"Updated server map: $collectionToServers from ClusterState $clusterState")
          else logger.info (s"Updated server map: $collectionToServers")
        }

        val newCollectionToServers = getCollectionToServers(clusterState)
        lastClusterState = clusterState

        if(newCollectionToServers != collectionToServers) warmupQueries match {
          case Some(warmup) => warmupNewServers(newCollectionToServers, warmup)
            .onComplete(_ => set(newCollectionToServers))
          case None => set(newCollectionToServers)
        }

      } catch {
        case NonFatal(e) =>
          logger.error(s"Could not process cluster state, server list might get outdated. Cluster state: $clusterState", e)
      }
    }
  }

  protected def warmupNewServers(newCollectionToServers: Map[String, IndexedSeq[SolrServer]],
                               warmup: WarmupQueries): Future[Iterable[Try[QueryResponse]]] = {
    import scala.concurrent.ExecutionContext.Implicits.global

    val perCollectionResponses = newCollectionToServers.flatMap { case (collection, solrServers) =>
      val existingServers = collectionToServers(collection)
      // SolrServer.equals checks both baseUrl and status, therefore we can just use contains
      val newActiveServers = solrServers.filter(s => s.isEnabled && !existingServers.contains(s))
      newActiveServers.map(warmupNewServer(collection, _, warmup.queriesByCollection(collection), warmup.count))
    }

    Future.sequence(perCollectionResponses).map(_.flatten)
  }

  protected def warmupNewServer(collection: String, s: SolrServer, queries: Seq[SolrQuery], count: Int): Future[Seq[Try[QueryResponse]]] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    // queries shall be run in parallel, one round after the other
    (1 to count).foldLeft(Future.successful(Seq.empty[Try[QueryResponse]])) { (res, round) =>
      res.flatMap { _ =>
        val warmupResponses = queries.map(q =>
          asyncSolrClient.doQuery(s, q)
            .map(Success(_))
            .recover {
            case NonFatal(e) =>
              logger.warn(s"Warmup query $q failed", e)
              Failure(e)
          }
        )
        Future.sequence(warmupResponses)
      }
    }
  }

  def shutdown = {
    maybeZk.foreach(_.close())
    scheduledExecutor.shutdownNow()
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
    collectionToServers.getOrElse(collection, Vector.empty)
  }

  // server state change

  private val serverChangeStateObservers = mutable.ListBuffer.empty[StateChangeObserver]

  override def register(listener: StateChangeObserver): Unit = {
    serverChangeStateObservers += listener
  }

  private def notifyObservers(oldState: Map[String, Seq[SolrServer]], newState: Map[String, Seq[SolrServer]]) = {
    CloudSolrServers.diff(oldState, newState).foreach { event =>
      serverChangeStateObservers.foreach(_.onStateChange(event))
    }
  }

}

object CloudSolrServers {

  private val logger = LoggerFactory.getLogger(classOf[CloudSolrServers])

  private[solrs] def getCollectionToServers(clusterState: ClusterState): Map[String, IndexedSeq[SolrServer]] = {
    import scala.collection.JavaConversions._

    clusterState.getCollectionsMap.foldLeft(
      Map.empty[String, IndexedSeq[SolrServer]].withDefaultValue(IndexedSeq.empty)
    ) { case (res, (name, collection)) =>
      val slices = collection.getSlices
      val servers = slices.flatMap(_.getReplicas.map(repl =>
        SolrServer(ZkCoreNodeProps.getCoreUrl(repl), serverStatus(repl))
      )).toIndexedSeq
      res.updated(name, res(name) ++ servers)
    }
  }

  private def serverStatus(replica: Replica): ServerStatus = replica.getState match {
    case Replica.State.ACTIVE => Enabled
    case Replica.State.RECOVERING => Disabled
    case Replica.State.RECOVERY_FAILED => Failed
    case Replica.State.DOWN => Failed
  }

  /**
   * Specifies how newly added servers / servers that changed from down to active are put under load.
   * @param queriesByCollection a function that returns warmup queries for a given collection.
   * @param count the number of times that the queries shall be run.s
   */
  case class WarmupQueries(queriesByCollection: String => Seq[SolrQuery], count: Int)

  private[solrs] def diff(oldState: Map[String, Seq[SolrServer]], newState: Map[String, Seq[SolrServer]]): Iterable[StateChange] = {

    import ServerStateChangeObservable._

    def changes(servers1: Seq[SolrServer], servers2: Seq[SolrServer],
                stateChanged: (SolrServer, SolrServer) => StateChanged,
                onlyInServers2: SolrServer => StateChange): Set[StateChange] = {
      servers2.foldLeft(Set.empty[StateChange]) { (res, server2) =>
        servers1.find(_.baseUrl == server2.baseUrl) match {
          case Some(server1) if server1.status == server2.status => res
          case Some(server1) => res + stateChanged(server1, server2)
          case None => res + onlyInServers2(server2)
        }
      }
    }

    val changesFromOldCollections = oldState.flatMap { case (collection, oldServers) =>
      val newServers = newState.getOrElse(collection, Nil)
      val changesFromOldState = changes(oldServers, newServers,
        stateChanged = (oldServer, newServer) => StateChanged(oldServer, newServer, collection),
        onlyInServers2 = (server2) => Added(server2, collection))
      val changesFromNewState = changes(newServers, oldServers,
        stateChanged = (newServer, oldServer) => StateChanged(oldServer, newServer, collection),
        onlyInServers2 = (server2) => Removed(server2, collection))
      changesFromOldState ++ changesFromNewState
    }

    val changesFromNewCollections = newState
      .filterKeys(newCollection => !oldState.contains(newCollection))
      .flatMap {
        case (collection, newServers) =>
          newServers.map(Added(_, collection))
      }

    changesFromOldCollections ++ changesFromNewCollections
  }

}

class ReloadingSolrServers(url: String, extractor: Array[Byte] => IndexedSeq[SolrServer], httpClient: AsyncHttpClient) extends SolrServers {

  private val logger = LoggerFactory.getLogger(getClass)

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
    val promise = Promise[Array[Byte]]()
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