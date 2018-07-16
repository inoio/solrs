package io.ino.solrs

import java.util
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory

import io.ino.solrs.CloudSolrServers.WarmupQueries
import io.ino.solrs.ServerStateChangeObservable.StateChange
import io.ino.solrs.future.Future
import io.ino.solrs.future.FutureFactory
import io.ino.solrs.future.JavaFutureFactory
import io.ino.solrs.future.ScalaFutureFactory
import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.SolrRequest
import org.apache.solr.client.solrj.SolrServerException
import org.apache.solr.client.solrj.request.QueryRequest
import org.apache.solr.client.solrj.response.QueryResponse
import org.apache.solr.common.cloud._
import org.apache.solr.common.params.ShardParams
import org.asynchttpclient.AsyncCompletionHandler
import org.asynchttpclient.AsyncHttpClient
import org.asynchttpclient.Response
import org.slf4j.LoggerFactory

import scala.collection.breakOut
import scala.collection.generic.CanBuildFrom
import scala.collection.mutable
import scala.util.Failure
import scala.util.Success
import scala.util.Try
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
   * Determines Solr servers matching the given solr request (e.g. based on the "collection" param).
   */
  def matching(r: SolrRequest[_]): Try[IndexedSeq[SolrServer]]
}

class StaticSolrServers(override val all: IndexedSeq[SolrServer]) extends SolrServers {
  override def matching(r: SolrRequest[_]): Try[IndexedSeq[SolrServer]] = Success(all)
}
object StaticSolrServers {
  def apply(baseUrls: IndexedSeq[String]): StaticSolrServers = new StaticSolrServers(baseUrls.map(SolrServer(_)))

  /* Java API */
  import scala.collection.JavaConverters._
  def create(baseUrls: java.lang.Iterable[String]): StaticSolrServers = apply(baseUrls.asScala.toIndexedSeq)
}

import java.util.concurrent.TimeUnit

import scala.concurrent.duration._

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
 * @param defaultCollection Optional default collection to use when the request does not specify the "collection" param.
 */
class CloudSolrServers[F[_]](zkHost: String,
                             zkClientTimeout: Duration = 15 seconds, /* default from Solr Core, see also SOLR-5221*/
                             zkConnectTimeout: Duration = 10 seconds, /* default from solrj CloudSolrServer*/
                             clusterStateUpdateInterval: Duration = 1 second,
                             defaultCollection: Option[String] = None,
                             warmupQueries: Option[WarmupQueries] = None)
                            (implicit futureFactory: FutureFactory[F]) extends SolrServers with AsyncSolrClientAware[F] with ServerStateChangeObservable {

  import CloudSolrServers._

  private var maybeZk: Option[ZkStateReader] = None

  @volatile
  private var collections = Map.empty[String, CollectionInfo]
  private def collectionToServers: Map[String, IndexedSeq[SolrServer]] = collections.mapValues(_.servers)

  private val scheduledExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(1, new ZkClusterStateUpdateTF)

  private var asyncSolrClient: AsyncSolrClient[F] = _

  override def setAsyncSolrClient(client: AsyncSolrClient[F]): Unit = {
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
      scheduleUpdateFromClusterState(zkStateReader)
    } catch {
      case NonFatal(e) =>
        logger.warn("Could not initialize ZkStateReader, this can happen when there are no solr servers connected." +
          s" Retrying in $zkConnectTimeout. Original exception: $e")
        scheduledExecutor.schedule(new Runnable {
          override def run(): Unit = initZkStateReader(zkStateReader)
        }, zkConnectTimeout.toMillis, TimeUnit.MILLISECONDS)
    }
  }

  private def scheduleUpdateFromClusterState(zkStateReader: ZkStateReader): Unit = {
    // reschedule after completion of the previous run, to prevent overlapping updates
    scheduledExecutor.schedule(new Runnable {
      override def run(): Unit = updateFromClusterState(zkStateReader).onComplete(_ =>
        scheduleUpdateFromClusterState(zkStateReader)
      )
    }, clusterStateUpdateInterval.toMillis, TimeUnit.MILLISECONDS)
  }

  /**
   * Updates the server list when the ZkStateReader clusterState changed
   */
  private def updateFromClusterState(zkStateReader: ZkStateReader): Future[Unit] = {
    // could perhaps be replaced with zkStateReader.registerCollectionStateWatcher(collection, watcher);

    val clusterState = zkStateReader.getClusterState

    def set(newCollections: Map[String, CollectionInfo]): Unit = {
      notifyObservers(collectionToServers, newCollections.mapValues(_.servers))
      collections = newCollections
      if (logger.isDebugEnabled) logger.debug (s"Updated server map: $collectionToServers from ClusterState $clusterState")
      else logger.info (s"Updated server map: $collectionToServers")
    }

    try {
      val newCollections = getCollections(clusterState)

      if (newCollections != collections) warmupQueries match {
        case Some(warmup) =>
          warmupNewServers(newCollections.mapValues(_.servers), warmup)
          .map(_ => set(newCollections))
        case None =>
          futureFactory.successful(set(newCollections))
      } else futureFactory.successful(())

    } catch {
      case NonFatal(e) =>
        logger.error(s"Could not process cluster state, server list might get outdated. Cluster state: $clusterState", e)
        futureFactory.failed(e)
    }
  }

  protected def warmupNewServers(newCollectionToServers: Map[String, IndexedSeq[SolrServer]],
                               warmup: WarmupQueries): Future[Iterable[Try[QueryResponse]]] = {

    val perCollectionResponses = newCollectionToServers.flatMap { case (collection, solrServers) =>
      val existingServers = collectionToServers.getOrElse(collection, IndexedSeq.empty)
      // SolrServer.equals checks both baseUrl and status, therefore we can just use contains
      val newActiveServers = solrServers.filter(s => s.isEnabled && !existingServers.contains(s))
      newActiveServers.map(warmupNewServer(collection, _, warmup.queriesByCollection(collection), warmup.count))
    }

    FutureFactory.sequence(perCollectionResponses).map(_.flatten)
  }

  protected def warmupNewServer(collection: String, s: SolrServer, queries: Seq[SolrQuery], count: Int): Future[Seq[Try[QueryResponse]]] = {
    // queries shall be run in parallel, one round after the other
    (1 to count).foldLeft(futureFactory.successful(Seq.empty[Try[QueryResponse]])) { (res, round) =>
      res.flatMap { _ =>
        val warmupResponses = queries.map(q =>
          asyncSolrClient.doExecute[QueryResponse](s, new QueryRequest(q))
            .map(Success(_))
            .handle {
            case NonFatal(e) =>
              logger.warn(s"Warmup query $q failed", e)
              Failure(e)
          }
        )
        FutureFactory.sequence(warmupResponses)
      }
    }
  }

  def shutdown(): Unit = {
    maybeZk.foreach(_.close())
    scheduledExecutor.shutdownNow()
    scheduledExecutor.awaitTermination(10, TimeUnit.SECONDS)
  }

  /**
   * The currently known solr servers.
   */
  @volatile
  override def all: IndexedSeq[SolrServer] = collections.values.flatMap(_.servers)(breakOut)

  /**
   * An infinite iterator over known solr servers. When the last item is reached,
   * it should start from the first one again. When the known solr servers change,
   * the iterator must reflect this.
   */
  override def matching(r: SolrRequest[_]): Try[IndexedSeq[SolrServer]] = {
    val params = r.getParams
    val collection = Option(params.get("collection")).orElse(defaultCollection).getOrElse(
      throw new SolrServerException("No collection param specified on request and no default collection has been set.")
    )
    collections.get(collection) match {
      case Some(CollectionInfo(docCollection, servers)) =>
        val shardKeys = params.get(ShardParams._ROUTE_)
        val slices = docCollection.getRouter.getSearchSlices(shardKeys, params, docCollection)
        val serverUrls: Set[String] = mapSliceReplicas(slices)(repl =>
          SolrServer.fixUrl(repl.getCoreUrl)
        )(breakOut)
        Success(servers.filter(server => serverUrls.contains(server.baseUrl)))
      case None =>
        Failure(UnknownCollectionException(collection))
    }
  }

  // server state change

  private val serverChangeStateObservers = mutable.ListBuffer.empty[StateChangeObserver]

  override def register(listener: StateChangeObserver): Unit = {
    serverChangeStateObservers += listener
  }

  private def notifyObservers(oldState: Map[String, Seq[SolrServer]], newState: Map[String, Seq[SolrServer]]): Unit = {
    CloudSolrServers.diff(oldState, newState).foreach { event =>
      serverChangeStateObservers.foreach(_.onStateChange(event))
    }
  }

}

object CloudSolrServers {

  private val logger = LoggerFactory.getLogger(getClass)

  final case class UnknownCollectionException(collection: String) extends IllegalArgumentException(
    s"The collection '$collection' is not known"
  )

  /* Java API */
  case class Builder(zkHost: String,
                     zkClientTimeout: Duration = 15 seconds, /* default from Solr Core, see also SOLR-5221*/
                     zkConnectTimeout: Duration = 10 seconds, /* default from solrj CloudSolrServer*/
                     clusterStateUpdateInterval: Duration = 1 second,
                     defaultCollection: Option[String] = None,
                     warmupQueries: Option[WarmupQueries] = None) {
    def withZkClientTimeout(value: Long, unit: TimeUnit): Builder = copy(zkClientTimeout = FiniteDuration(value, unit))
    def withZkConnectTimeout(value: Long, unit: TimeUnit): Builder = copy(zkConnectTimeout = FiniteDuration(value, unit))
    def withClusterStateUpdateInterval(value: Long, unit: TimeUnit): Builder = copy(clusterStateUpdateInterval = FiniteDuration(value, unit))
    def withDefaultCollection(collection: String): Builder = copy(defaultCollection = Some(collection))
    import java.lang.{Iterable => JIterable}
    import java.util.function.{Function => JFunction}
    def withWarmupQueries(queriesByCollection: JFunction[String, JIterable[SolrQuery]], count: Int): Builder = {
      def delegate(collection: String): Seq[SolrQuery] = {
        val res = queriesByCollection(collection)
        import scala.collection.JavaConverters._
        res.asScala.toList
      }
      copy(warmupQueries = Some(WarmupQueries(delegate, count)))
    }

    def build(): CloudSolrServers[CompletionStage] = new CloudSolrServers(zkHost, zkConnectTimeout, zkConnectTimeout, clusterStateUpdateInterval, defaultCollection, warmupQueries)(JavaFutureFactory)

    def build[F[_]](implicit futureFactory: FutureFactory[F]): CloudSolrServers[F] =
      new CloudSolrServers(zkHost, zkConnectTimeout, zkConnectTimeout, clusterStateUpdateInterval, defaultCollection, warmupQueries)
  }

  /* Java API */
  def builder(zkHost: String): Builder = Builder(zkHost)

  private[CloudSolrServers] final case class CollectionInfo(collection: DocCollection, servers: IndexedSeq[SolrServer])

  private[solrs] def getCollections(clusterState: ClusterState): Map[String, CollectionInfo] = {
    import scala.collection.JavaConverters._

    clusterState.getCollectionsMap.asScala.foldLeft(
      Map.empty[String, CollectionInfo]
    ) { case (res, (name, collection)) =>
      val servers = mapSliceReplicas(collection.getSlices) { repl =>
        // according to org.apache.solr.common.cloud.ZkCoreNodeProps.isLeader
        val isLeader = repl.containsKey(ZkStateReader.LEADER_PROP)
        SolrServer(repl.getCoreUrl, serverStatus(repl), isLeader)
      }.toIndexedSeq
      res.updated(name, CollectionInfo(collection, servers))
    }
  }

  private def mapSliceReplicas[A, T <: Iterable[A]](slices: util.Collection[Slice])
                                                   (fun: Replica => A)
                                                   (implicit cbf: CanBuildFrom[Iterable[Slice], A, T]): T = {
    import scala.collection.JavaConverters.collectionAsScalaIterableConverter
    slices.asScala.flatMap(_.getReplicas.asScala.map(repl =>
      fun(repl)
    ))(breakOut)
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
          case Some(server1) if server1 == server2 => res
          case Some(server1) => res + stateChanged(server1, server2)
          case None => res + onlyInServers2(server2)
        }
      }
    }

    val changesFromOldCollections = oldState.flatMap { case (collection, oldServers) =>
      val newServers = newState.getOrElse(collection, Nil)
      val changesFromOldState = changes(oldServers, newServers,
        stateChanged = (oldServer, newServer) => StateChanged(oldServer, newServer, collection),
        onlyInServers2 = server2 => Added(server2, collection))
      val changesFromNewState = changes(newServers, oldServers,
        stateChanged = (newServer, oldServer) => StateChanged(oldServer, newServer, collection),
        onlyInServers2 = server2 => Removed(server2, collection))
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

class ReloadingSolrServers[F[_]](url: String, extractor: Array[Byte] => IndexedSeq[SolrServer], httpClient: AsyncHttpClient)
                                (implicit futureFactory: FutureFactory[F] = ScalaFutureFactory) extends SolrServers {

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
  override def matching(r: SolrRequest[_]): Try[IndexedSeq[SolrServer]] = Success(solrServers)

  def reload(): F[IndexedSeq[SolrServer]] = {
    val f = loadUrl().map { data =>
      // TODO: check if solr servers actually changed, perhaps only add/remove changed stuff
      // or somehow preserve the status of servers
      val oldServers = solrServers
      solrServers = extractor(data)
      logger.info(s"Changed solr servers from $oldServers to $solrServers")
      solrServers
    }
    futureFactory.toBase(f)
  }

  protected def loadUrl(): Future[Array[Byte]] = {
    val promise = futureFactory.newPromise[Array[Byte]]
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