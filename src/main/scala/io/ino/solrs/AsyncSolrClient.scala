package io.ino.solrs

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, IOException}
import java.util.Arrays.asList
import java.util.Locale
import java.util.concurrent.{ScheduledExecutorService, TimeUnit}

import io.ino.solrs.AsyncSolrClient.Builder
import io.ino.solrs.HttpUtils._
import io.ino.solrs.RetryDecision.Result
import io.ino.solrs.SolrResponseFactory._
import io.ino.solrs.future.Future
import io.ino.solrs.future.FutureFactory
import org.apache.commons.io.IOUtils
import org.apache.solr.client.solrj.beans.DocumentObjectBinder
import org.apache.solr.client.solrj.impl.BinaryRequestWriter
import org.apache.solr.client.solrj.impl.BinaryResponseParser
import org.apache.solr.client.solrj.impl.StreamingBinaryResponseParser
import org.apache.solr.client.solrj.request.AbstractUpdateRequest.ACTION.COMMIT
import org.apache.solr.client.solrj.request.AbstractUpdateRequest.ACTION.OPTIMIZE
import org.apache.solr.client.solrj.request.QueryRequest
import org.apache.solr.client.solrj.request.RequestWriter
import org.apache.solr.client.solrj.request.SolrPing
import org.apache.solr.client.solrj.request.UpdateRequest
import org.apache.solr.client.solrj.response.QueryResponse
import org.apache.solr.client.solrj.response.SolrPingResponse
import org.apache.solr.client.solrj.response.UpdateResponse
import org.apache.solr.client.solrj.ResponseParser
import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.SolrRequest
import org.apache.solr.client.solrj.SolrRequest.METHOD
import org.apache.solr.client.solrj.SolrRequest.METHOD.GET
import org.apache.solr.client.solrj.SolrRequest.METHOD.POST
import org.apache.solr.client.solrj.SolrResponse
import org.apache.solr.client.solrj.SolrServerException
import org.apache.solr.client.solrj.StreamingResponseCallback
import org.apache.solr.common.SolrDocument
import org.apache.solr.common.SolrDocumentList
import org.apache.solr.common.SolrException
import org.apache.solr.common.SolrException.ErrorCode.BAD_REQUEST
import org.apache.solr.common.SolrInputDocument
import org.apache.solr.common.StringUtils
import org.apache.solr.common.params.CommonParams
import org.apache.solr.common.params.ModifiableSolrParams
import org.apache.solr.common.params.SolrParams
import org.apache.solr.common.util.NamedList
import org.asynchttpclient.DefaultAsyncHttpClient
import org.asynchttpclient.AsyncCompletionHandler
import org.asynchttpclient.AsyncHttpClient
import org.asynchttpclient.Response
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.collection.JavaConverters._
import scala.util.control.NonFatal

object AsyncSolrClient {

  /* The function that creates that actual instance of AsyncSolrClient */
  private[solrs] type ASCFactory[F[_], ASC <: AsyncSolrClient[F]] = (LoadBalancer, AsyncHttpClient, /*shutdownHttpClient*/ Boolean,
    Option[RequestInterceptor], RequestWriter, ResponseParser, Metrics, Option[ServerStateObservation[F]], RetryPolicy) => ASC

  def apply[F[_]](baseUrl: String)(implicit futureFactory: FutureFactory[F]): AsyncSolrClient[F] =
    new Builder(new SingleServerLB(baseUrl), ascFactory[F] _).build
  def apply[F[_]](loadBalancer: LoadBalancer)(implicit futureFactory: FutureFactory[F]): AsyncSolrClient[F] =
    new Builder(loadBalancer, ascFactory[F] _).build

  object Builder {
    def apply[F[_]](baseUrl: String)(implicit futureFactory: FutureFactory[F]) =
      new Builder(baseUrl, ascFactory[F] _)
    def apply[F[_]](loadBalancer: LoadBalancer)(implicit futureFactory: FutureFactory[F]) =
      new Builder(loadBalancer, ascFactory[F] _)
  }

  private[solrs] def ascFactory[F[_]](loadBalancer: LoadBalancer,
                                      httpClient: AsyncHttpClient,
                                      shutdownHttpClient: Boolean,
                                      requestInterceptor: Option[RequestInterceptor],
                                      requestWriter: RequestWriter,
                                      responseParser: ResponseParser,
                                      metrics: Metrics,
                                      serverStateObservation: Option[ServerStateObservation[F]],
                                      retryPolicy: RetryPolicy)(implicit futureFactory: FutureFactory[F]): AsyncSolrClient[F] =
    new AsyncSolrClient[F](
      loadBalancer,
      httpClient,
      shutdownHttpClient,
      requestInterceptor,
      requestWriter,
      responseParser,
      metrics,
      serverStateObservation,
      retryPolicy
    )

  case class Builder[F[_], ASC <: AsyncSolrClient[F]] protected (loadBalancer: LoadBalancer,
                                                                 httpClient: Option[AsyncHttpClient],
                                                                 shutdownHttpClient: Boolean,
                                                                 requestInterceptor: Option[RequestInterceptor] = None,
                                                                 requestWriter: Option[RequestWriter] = None,
                                                                 responseParser: Option[ResponseParser] = None,
                                                                 metrics: Option[Metrics] = None,
                                                                 serverStateObservation: Option[ServerStateObservation[F]] = None,
                                                                 retryPolicy: RetryPolicy = RetryPolicy.TryOnce,
                                                                 factory: ASCFactory[F, ASC])(implicit futureFactory: FutureFactory[F]) {

    def this(loadBalancer: LoadBalancer, factory: ASCFactory[F, ASC])(implicit futureFactory: FutureFactory[F]) = this(loadBalancer, None, true, factory = factory)
    def this(baseUrl: String, factory: ASCFactory[F, ASC])(implicit futureFactory: FutureFactory[F]) = this(new SingleServerLB(baseUrl), factory = factory)

    def withHttpClient(httpClient: AsyncHttpClient): Builder[F, ASC] = {
      copy(httpClient = Some(httpClient), shutdownHttpClient = false)
    }

    def withRequestInterceptor(requestInterceptor: RequestInterceptor): Builder[F, ASC] = {
      copy(requestInterceptor = Some(requestInterceptor))
    }

    def withRequestWriter(requestWriter: RequestWriter): Builder[F, ASC] = {
      copy(requestWriter = Some(requestWriter))
    }

    def withResponseParser(responseParser: ResponseParser): Builder[F, ASC] = {
      copy(responseParser = Some(responseParser))
    }

    def withMetrics(metrics: Metrics): Builder[F, ASC] = {
      copy(metrics = Some(metrics))
    }

    /**
      * Configures server state observation using the given observer and the provided interval.
      */
    def withServerStateObservation(serverStateObserver: ServerStateObserver[F],
                                   checkInterval: FiniteDuration,
                                   executorService: ScheduledExecutorService): Builder[F, ASC] = {
      copy(serverStateObservation = Some(ServerStateObservation[F](serverStateObserver, checkInterval, executorService, futureFactory)))
    }

    /**
      * Configure the retry policy to apply for failed requests.
      */
    def withRetryPolicy(retryPolicy: RetryPolicy): Builder[F, ASC] = {
      copy(retryPolicy = retryPolicy)
    }

    protected def createHttpClient: AsyncHttpClient = new DefaultAsyncHttpClient()

    protected def createRequestWriter: RequestWriter = new BinaryRequestWriter

    protected def createResponseParser: ResponseParser = new BinaryResponseParser

    protected def createMetrics: Metrics = NoopMetrics

    // the load balancer and others might need to access this instance, extracted as protected method to be overridable from tests
    protected def setOnAsyncSolrClientAwares(solr: AsyncSolrClient[F]): Unit = {

      def set(maybeAware: AnyRef, solr: AsyncSolrClient[F]): Unit = maybeAware match {
        case aware: AsyncSolrClientAware[F] => aware.setAsyncSolrClient(solr)
        case _ => // nothing to do
      }

      // the solr servers should be able to probe servers before the load balancer gets the handle...
      // it's also set here (instead of letting the LoadBalancer pass the solrs instance to SolrServers),
      // so that a LoadBalancer subclass cannot not forget to invoke super
      set(loadBalancer.solrServers, solr)
      set(loadBalancer, solr)
    }

    def build: ASC = {
      val res = factory(
        loadBalancer,
        httpClient.getOrElse(createHttpClient),
        shutdownHttpClient,
        requestInterceptor,
        requestWriter.getOrElse(createRequestWriter),
        responseParser.getOrElse(createResponseParser),
        metrics.getOrElse(createMetrics),
        serverStateObservation,
        retryPolicy
      )
      setOnAsyncSolrClientAwares(res)
      res
    }
  }

}

/**
  * Async, non-blocking Solr Server that allows to make requests to Solr.
  * The usage shall be similar to the <a href="https://wiki.apache.org/solr/Solrj">solrj SolrServer</a>,
  * so request returns a future of a {@link SolrResponse}.
  *
  * @author <a href="martin.grotzke@inoio.de">Martin Grotzke</a>
  */
class AsyncSolrClient[F[_]] protected (private[solrs] val loadBalancer: LoadBalancer,
                                       httpClient: AsyncHttpClient,
                                       shutdownHttpClient: Boolean,
                                       requestInterceptor: Option[RequestInterceptor] = None,
                                       requestWriter: RequestWriter = new BinaryRequestWriter,
                                       responseParser: ResponseParser = new BinaryResponseParser,
                                       metrics: Metrics = NoopMetrics,
                                       serverStateObservation: Option[ServerStateObservation[F]] = None,
                                       retryPolicy: RetryPolicy = RetryPolicy.TryOnce)(implicit futureFactory: FutureFactory[F]) {

  private val UTF_8 = "UTF-8"
  private val DEFAULT_PATH = "/select"

  /**
   * User-Agent String.
   */
  val AGENT = "Solr[" + classOf[AsyncSolrClient[F]].getName() + "] 1.0"

  private val logger = LoggerFactory.getLogger(getClass())

  private val cancellableObservation = serverStateObservation.map { observation =>
    val task: Runnable = new Runnable() {
      override def run(): Unit = {
        observation.serverStateObserver.checkServerStatus()
      }
    }
    observation.executorService.scheduleWithFixedDelay(task, 0L, observation.checkInterval.toMillis, TimeUnit.MILLISECONDS)
  }

  private lazy val binder = new DocumentObjectBinder

  private def sanitize(baseUrl: String): String = {
    if (baseUrl.endsWith("/")) {
      baseUrl.substring(0, baseUrl.length() - 1)
    }
    else if (baseUrl.indexOf('?') >= 0) {
      throw new RuntimeException("Invalid base url for solrj.  The base URL must not contain parameters: " + baseUrl)
    }
    else
      baseUrl
  }

  private def updateRequest(collection: Option[String], commitWithinMs: Int = -1): UpdateRequest = {
    val req = new UpdateRequest
    collection.foreach(req.setParam("collection", _))
    req.setCommitWithin(commitWithinMs)
    req
  }

  private def queryParams(collection: Option[String], q: Option[SolrParams]): ModifiableSolrParams = {
    val reqParams = new ModifiableSolrParams(q.orNull)
    collection.foreach(reqParams.set("collection", _))
    reqParams
  }

  /**
   * Closes the http client (asynchronously) if it was not provided but created by this class.
   */
  def shutdown(): Unit = {
    cancellableObservation.foreach(_.cancel(true))
    if(shutdownHttpClient) {
      httpClient.close()
    }
    loadBalancer.shutdown()
  }

  /**
    * Performs a request to a solr server.
    * @param r the request to send to solr.
    * @return
    */
  def execute[T <: SolrResponse : SolrResponseFactory](r: SolrRequest[_ <: T]): F[T] = futureFactory.toBase[T](
    loadBalanceRequest(RequestContext[T](r)).map(_._1)
  )

  /**
    * Performs a request to a solr server taking the preferred server into account if provided.
    * @param r the request to send to the solr server.
    * @param preferred the server that should be preferred to process the request. Specific [[io.ino.solrs.LoadBalancer LoadBalancer]]
    *                  implementations have to support this and might add their own semantics.
    * @return the response and the server that handled the request.
    */
  def executePreferred[T <: SolrResponse : SolrResponseFactory](r: SolrRequest[_ <: T], preferred: Option[SolrServer]): F[(T, SolrServer)] =
    futureFactory.toBase[(T, SolrServer)](loadBalanceRequest(RequestContext[T](r, preferred)))

  /**
    * Adds a collection of documents, specifying max time before they become committed
    *
    * @param collection     the Solr collection to add documents to
    * @param docs           the collection of documents
    * @param commitWithinMs max time (in ms) before a commit will happen
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} from the server
    */
  def addDocs(collection: Option[String] = None, docs: Iterable[SolrInputDocument], commitWithinMs: Int = -1): F[UpdateResponse] =
    execute(updateRequest(collection, commitWithinMs).add(docs.asJavaCollection))

  /**
    * Adds the documents supplied by the given iterator.
    *
    * @param collection  the Solr collection to add documents to
    * @param docIterator the iterator which returns SolrInputDocument instances
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} from the server
    */
  def addDocs(collection: String, docIterator: Iterator[SolrInputDocument]): F[UpdateResponse] = {
    val req = updateRequest(Some(collection))
    req.setDocIterator(docIterator.asJava)
    execute(req)
  }

  /**
    * Adds the documents supplied by the given iterator.
    *
    * @param docIterator the iterator which returns SolrInputDocument instances
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} from the server
    */
  def addDocs(docIterator: Iterator[SolrInputDocument]): F[UpdateResponse] = {
    val req = updateRequest(None)
    req.setDocIterator(docIterator.asJava)
    execute(req)
  }

  /**
    * Adds a single document specifying max time before it becomes committed
    *
    * @param collection     the Solr collection to add the document to
    * @param doc            the input document
    * @param commitWithinMs max time (in ms) before a commit will happen
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} from the server
    */
  def addDoc(collection: Option[String] = None, doc: SolrInputDocument, commitWithinMs: Int = -1): F[UpdateResponse] =
    execute(updateRequest(collection, commitWithinMs).add(doc))

  /**
    * Adds a single bean specifying max time before it becomes committed
    * The bean is converted to a {@link SolrInputDocument} by the client's
    * {@link org.apache.solr.client.solrj.beans.DocumentObjectBinder}
    *
    * @param collection to Solr collection to add documents to
    * @param obj        the input bean
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} from the server
    */
  def addBean(collection: Option[String] = None, obj: Any, commitWithinMs: Int = -1): F[UpdateResponse] =
    addDoc(collection, binder.toSolrInputDocument(obj), commitWithinMs)

  /**
    * Adds a collection of beans specifying max time before they become committed
    * The beans are converted to {@link SolrInputDocument}s by the client's
    * {@link org.apache.solr.client.solrj.beans.DocumentObjectBinder}
    *
    * @param collection     the Solr collection to add documents to
    * @param beans          the collection of beans
    * @param commitWithinMs max time (in ms) before a commit will happen
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} from the server
    */
  def addBeans(collection: Option[String] = None, beans: Iterable[_], commitWithinMs: Int = -1): F[UpdateResponse] =
    addDocs(collection, beans.map(binder.toSolrInputDocument), commitWithinMs)

  /**
    * Adds the beans supplied by the given iterator.
    *
    * @param collection   the Solr collection to add the documents to
    * @param beanIterator the iterator which returns Beans
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} from the server
    */
  def addBeans(collection: String, beanIterator: Iterator[_]): F[UpdateResponse] =
    addDocs(collection, beanIterator.map(binder.toSolrInputDocument))

  /**
    * Adds the beans supplied by the given iterator.
    *
    * @param beanIterator the iterator which returns Beans
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} from the server
    */
  def addBeans(beanIterator: Iterator[_]): F[UpdateResponse] =
    addDocs(beanIterator.map(binder.toSolrInputDocument))

  /**
    * Performs an explicit commit, causing pending documents to be committed for indexing
    *
    * @param collection   the Solr collection to send the commit to
    * @param waitFlush    block until index changes are flushed to disk
    * @param waitSearcher block until a new searcher is opened and registered as the
    *                     main query searcher, making the changes visible
    * @param softCommit   makes index changes visible while neither fsync-ing index files
    *                     nor writing a new index descriptor
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} containing the response
    *         from the server
    */
  def commit(collection: Option[String] = None, waitFlush: Boolean = true, waitSearcher: Boolean = true, softCommit: Boolean = false): F[UpdateResponse] =
    execute(updateRequest(collection).setAction(COMMIT, waitFlush, waitSearcher, softCommit))

  /**
    * Performs an explicit optimize, causing a merge of all segments to one.
    * Note: In most cases it is not required to do explicit optimize
    *
    * @param collection   the Solr collection to send the optimize to
    * @param waitFlush    block until index changes are flushed to disk
    * @param waitSearcher block until a new searcher is opened and registered as
    *                     the main query searcher, making the changes visible
    * @param maxSegments  optimizes down to at most this number of segments
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} containing the response
    *         from the server
    */
  def optimize(collection: Option[String] = None, waitFlush: Boolean = true, waitSearcher: Boolean = true, maxSegments: Int = 1): F[UpdateResponse] =
    execute(updateRequest(collection).setAction(OPTIMIZE, waitFlush, waitSearcher, maxSegments))

  /**
    * Performs a rollback of all non-committed documents pending.
    * Note that this is not a true rollback as in databases. Content you have previously
    * added may have been committed due to autoCommit, buffer full, other client performing
    * a commit etc.
    *
    * @param collection the Solr collection to send the rollback to
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} containing the response
    *         from the server
    */
  def rollback(collection: Option[String] = None): F[UpdateResponse] =
    execute(updateRequest(collection).rollback())

  /**
    * Deletes a single document by unique ID, specifying max time before commit
    *
    * @param collection     the Solr collection to delete the document from
    * @param id             the ID of the document to delete
    * @param commitWithinMs max time (in ms) before a commit will happen
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} containing the response
    *         from the server
    */
  def deleteById(collection: Option[String] = None, id: String, commitWithinMs: Int = -1): F[UpdateResponse] =
    execute(updateRequest(collection, commitWithinMs).deleteById(id))

  /**
    * Deletes a list of documents by unique ID, specifying max time before commit
    *
    * @param collection     the Solr collection to delete the documents from
    * @param ids            the list of document IDs to delete
    * @param commitWithinMs max time (in ms) before a commit will happen
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} containing the response
    *         from the server
    */
  def deleteByIds(collection: Option[String] = None, ids: Seq[String], commitWithinMs: Int = -1): F[UpdateResponse] =
    execute(updateRequest(collection, commitWithinMs).deleteById(ids.asJava))

  /**
    * Deletes documents from the index based on a query, specifying max time before commit
    *
    * @param collection     the Solr collection to delete the documents from
    * @param query          the query expressing what documents to delete
    * @param commitWithinMs max time (in ms) before a commit will happen
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} containing the response
    *         from the server
    */
  def deleteByQuery(collection: Option[String] = None, query: String, commitWithinMs: Int = -1): F[UpdateResponse] =
    execute(updateRequest(collection, commitWithinMs).deleteByQuery(query))

  /**
    * Issues a ping request to check if the server is alive
    *
    * @return a { @link org.apache.solr.client.solrj.response.SolrPingResponse} containing the response
    *         from the server
    */
  def ping(): F[SolrPingResponse] = execute(new SolrPing())

  /**
    * Performs a query to the Solr server
    *
    * @param q an object holding all key/value parameters to send along the request
    * @return a { @link org.apache.solr.client.solrj.response.QueryResponse} containing the response
    *         from the server
    */
  def query(q: SolrParams): F[QueryResponse] = query(q, GET)

  /**
    * Performs a query to the Solr server
    *
    * @param q      an object holding all key/value parameters to send along the request
    * @param method specifies the HTTP method to use for the request, such as GET or POST
    * @return a { @link org.apache.solr.client.solrj.response.QueryResponse} containing the response
    *         from the server
    */
  def query(q: SolrParams, method: METHOD): F[QueryResponse] = execute(new QueryRequest(q, method))

  /**
    * Performs a query to the Solr server
    *
    * @param collection the Solr collection to query
    * @param q          an object holding all key/value parameters to send along the request
    * @param method     specifies the HTTP method to use for the request, such as GET or POST
    * @return a { @link org.apache.solr.client.solrj.response.QueryResponse} containing the response
    *         from the server
    */
  def query(collection: String, q: SolrParams, method: METHOD = GET): F[QueryResponse] =
    execute(new QueryRequest(queryParams(Some(collection), Some(q)), method))

  /**
    * Query solr, and stream the results.  Unlike the standard query, this will
    * send events for each Document rather then add them to the QueryResponse.
    *
    * Although this function returns a 'QueryResponse' it should be used with care
    * since it excludes anything that was passed to callback.  Also note that
    * future version may pass even more info to the callback and may not return
    * the results in the QueryResponse.
    *
    * @param collection the Solr collection to query
    * @param q          an object holding all key/value parameters to send along the request
    * @param callback   the callback to stream results to
    * @return a { @link org.apache.solr.client.solrj.response.QueryResponse} containing the response
    *         from the server
    */
  def queryAndStreamResponse(collection: Option[String] = None, q: SolrParams, callback: StreamingResponseCallback): F[QueryResponse] = {
    val parser = new StreamingBinaryResponseParser(callback)
    val req = new QueryRequest(queryParams(collection, Some(q)))
    req.setStreamingResponseCallback(callback)
    req.setResponseParser(parser)
    execute(req)
  }

  /**
    * Retrieves the SolrDocument associated with the given identifier and uses
    * the SolrParams to execute the request.
    *
    * @param collection the Solr collection to query
    * @param id         the id
    * @param params     additional parameters to add to the query
    * @return retrieved SolrDocument, or None if no document is found.
    */
  def getById(collection: Option[String] = None, id: String, params: Option[SolrParams] = None): F[Option[SolrDocument]] =
    futureFactory.toBase[Option[SolrDocument]](doGetByIds(collection, Iterable(id), params).map(_.asScala.headOption))

  /**
    * Retrieves the SolrDocuments associated with the given identifiers and uses
    * the SolrParams to execute the request.
    *
    * If a document was not found, it will not be added to the SolrDocumentList.
    *
    * @param collection the Solr collection to query
    * @param ids        the ids
    * @param params     additional parameters to add to the query
    * @return a SolrDocumentList
    */
  def getByIds(collection: Option[String] = None, ids: Iterable[String], params: Option[SolrParams] = None): F[SolrDocumentList] =
    futureFactory.toBase[SolrDocumentList](doGetByIds(collection, ids, params))

  /**
   * Performs a query to a solr server taking the preferred server into account if provided.
   * @param q the query to send to the solr server.
   * @param preferred the server that should be preferred to process the query. Specific [[io.ino.solrs.LoadBalancer LoadBalancer]]
   *                  implementations have to support this and might add their own semantics.
   * @return the response and the server that handled the query.
   */
  def queryPreferred(q: SolrQuery, preferred: Option[SolrServer]): F[(QueryResponse, SolrServer)] =
    executePreferred(new QueryRequest(q), preferred)

  private def doGetByIds(collection: Option[String], ids: Iterable[String], params: Option[SolrParams]): Future[SolrDocumentList] = {
    if (ids == null || ids.isEmpty) throw new IllegalArgumentException("Must provide an identifier of a document to retrieve.")
    val reqParams = queryParams(collection, params)
    if (StringUtils.isEmpty(reqParams.get(CommonParams.QT))) reqParams.set(CommonParams.QT, "/get")
    reqParams.set("ids", ids.toArray: _*)
    loadBalanceRequest(RequestContext(new QueryRequest(reqParams))).map(_._1).map(_.getResults)
  }

  private def loadBalanceRequest[T <: SolrResponse : SolrResponseFactory](requestContext: RequestContext[T]): Future[(T, SolrServer)] = {
    loadBalancer.solrServer(requestContext.r, requestContext.preferred) match {
      case Some(solrServer) =>
        executeWithRetries(solrServer, requestContext)
      case None =>
        val msg =
          if(requestContext.failedRequests.isEmpty) "No solr server available."
          else s"No next solr server available. These requests failed:\n- ${requestContext.failedRequests.mkString("\n- ")}"
        futureFactory.failed(new SolrServerException(msg))
    }
  }

  private def executeWithRetries[T <: SolrResponse : SolrResponseFactory](server: SolrServer, requestContext: RequestContext[T]): Future[(T, SolrServer)] = {
    val start = System.currentTimeMillis()
    execute[T](server, requestContext.r)
      .map(_ -> server)
      .handleWith { case NonFatal(e) =>
        val updatedContext = requestContext.failedRequest(server, (System.currentTimeMillis() - start) millis, e)
        retryPolicy.shouldRetry(e, server, updatedContext, loadBalancer) match {
          case RetryServer(s) =>
            logger.warn(s"Request failed for server $server, trying next server $s. Exception was: $e")
            executeWithRetries(s, updatedContext)
          case StandardRetryDecision(Result.Retry) =>
            logger.warn(s"Request failed for server $server, trying to get another server from loadBalancer for retry. Exception was: $e")
            loadBalanceRequest(updatedContext)
          case StandardRetryDecision(Result.Fail) =>
            logger.warn(s"Request failed for server $server, not retrying. Exception was: $e", e)
            // Wrap SolrException with solrs RemoteSolrException
            val ex = if(e.isInstanceOf[SolrException]) new RemoteSolrException(500, e.getMessage, e) else e
            futureFactory.failed(ex)
        }
      }
  }

  private def execute[T <: SolrResponse : SolrResponseFactory](solrServer: SolrServer, r: SolrRequest[_ <: T]): Future[T] = {
    val monitoredRequest = loadBalancer.interceptRequest[T](doExecute[T] _) _
    requestInterceptor.map(ri =>
      ri.interceptRequest[T](monitoredRequest)(solrServer, r)
    ).getOrElse(monitoredRequest(solrServer, r))
  }

  private[solrs] def doExecute[T <: SolrResponse : SolrResponseFactory](solrServer: SolrServer, r: SolrRequest[_ <: T]): Future[T] = {

    val wparams = new ModifiableSolrParams(r.getParams())
    if (responseParser != null) {
      wparams.set(CommonParams.WT, responseParser.getWriterType())
      wparams.set(CommonParams.VERSION, responseParser.getVersion())
    }

    implicit val s = solrServer

    val promise = futureFactory.newPromise[T]
    val startTime = System.currentTimeMillis()

    val url = solrServer.baseUrl + getPath(r)

    // the new Solr7 ContentWriter interface
    val maybeContentWriter = Option(requestWriter.getContentWriter(r))

    val requestBuilder = if (r.getMethod == GET) {
      val fullQueryUrl = url + wparams.toQueryString
      if (maybeContentWriter.isDefined) {
        throw new SolrException(BAD_REQUEST, "GET can't use ContentWriter")
      }
      httpClient.prepareGet(fullQueryUrl)
    } else {
      maybeContentWriter.map { contentWriter =>
        // POST/PUT with contentWriter
        val fullQueryUrl = url + wparams.toQueryString
        val req = if (r.getMethod == POST) httpClient.preparePost(fullQueryUrl) else httpClient.preparePut(fullQueryUrl)

        // AsyncHttpClient needs InputStream, need to adapt the writer
        val baos = new BinaryRequestWriter.BAOS()
        contentWriter.write(baos)
        val is = new ByteArrayInputStream(baos.getbuf(), 0, baos.size())

        req.setHeader("Content-Type", contentWriter.getContentType)
          .setBody(is)
      }.getOrElse {
        // POST/PUT with FORM data
        val req = if (r.getMethod == POST) httpClient.preparePost(url) else httpClient.preparePut(url)
        req.setFormParams(wparams.getMap.asScala.mapValues(asList[String](_: _*)).asJava)
      }
    }
    val request = requestBuilder.addHeader("User-Agent", AGENT).build()

    try {
      httpClient.executeRequest(request, new AsyncCompletionHandler[Response]() {
        override def onCompleted(response: Response): Response = {
          try {
            val qr = toSolrResponse[T](r, response, url, startTime)
            promise.success(qr)
          } catch {
            case NonFatal(e) => promise.failure(e)
          }
          response
        }

        override def onThrowable(t: Throwable) {
          metrics.countException
          promise.failure(t)
        }
      })
    } catch {
      case NonFatal(e) =>
        metrics.countException
        promise.failure(e)
    }

    promise.future
  }

  protected def getPath(request: SolrRequest[_ <: SolrResponse]): String = {
    val path = requestWriter.getPath(request)
    if (path != null && path.startsWith("/")) path else DEFAULT_PATH
  }

  @throws[RemoteSolrException]
  protected def toSolrResponse[T <: SolrResponse : SolrResponseFactory](r: SolrRequest[_ <: T], response: Response, url: String, startTime: Long)(implicit server: SolrServer): T = {
    var rsp: NamedList[Object] = null

    validateResponse(response, responseParser)

    val httpStatus = response.getStatusCode()

    try {
      val charset = getContentCharSet(response.getContentType).orNull
      rsp = responseParser.processResponse(response.getResponseBodyAsStream(), charset)
    } catch {
      case NonFatal(e) =>
        metrics.countRemoteException
        throw new RemoteSolrException(httpStatus, e.getMessage(), e)
    }

    if (httpStatus != 200) {
      metrics.countRemoteException
      val reason = getErrorReason(url, rsp, response)
      throw new RemoteSolrException(httpStatus, reason, null)
    }

    val res = SolrResponseFactory[T].createResponse(r)
    res.setResponse(rsp)
    val elapsedTime = System.currentTimeMillis() - startTime
    res.setElapsedTime(elapsedTime)
    metrics.requestTime(elapsedTime)
    res
  }

  @throws[RemoteSolrException]
  private def validateResponse(response: Response, responseParser: ResponseParser)(implicit server: SolrServer) {
    validateMimeType(responseParser.getContentType, response)

    val httpStatus = response.getStatusCode
    if(httpStatus >= 400) {
      metrics.countRemoteException
      val msg = responseParser.processResponse(response.getResponseBodyAsStream, getResponseEncoding(response)).get("error").asInstanceOf[NamedList[_]].get("msg")
      throw new RemoteSolrException(httpStatus, s"Server at ${server.baseUrl} returned non ok status:$httpStatus, message: ${response.getStatusText}, $msg", null)
    }
  }

  @throws[RemoteSolrException]
  protected def validateMimeType(expectedContentType: String, response: Response) {
    if (expectedContentType != null) {
      val expectedMimeType = getMimeType(expectedContentType).map(_.toLowerCase(Locale.ROOT)).getOrElse("")
      val actualMimeType = getMimeType(response.getContentType).map(_.toLowerCase(Locale.ROOT)).getOrElse("")
      if (expectedMimeType != actualMimeType) {
        var msg = s"Expected mime type [$expectedMimeType] but got [$actualMimeType]."
        var encoding: String = getResponseEncoding(response)
        try {
          // might be solved with responseParser.processResponse (like it's done for 4xx codes)
          msg = msg + "\n" + IOUtils.toString(response.getResponseBodyAsStream, encoding)
        }
        catch {
          case e: IOException => {
            metrics.countRemoteException
            throw new RemoteSolrException(response.getStatusCode, s"$msg Unfortunately could not parse response (for debugging) with encoding $encoding", e)
          }
        }
        metrics.countRemoteException
        throw new RemoteSolrException(response.getStatusCode, msg, null)
      }
    }
  }

  protected def getResponseEncoding(response: Response): String = {
    var encoding = response.getHeader("Content-Encoding")
    if (encoding == null) "UTF-8" else encoding
  }

  protected def getErrorReason(url: String, rsp: NamedList[_], response: Response): String = {
    var reason: String = null
    try {
      val err = rsp.get("error")
      if (err != null) {
        reason = err.asInstanceOf[NamedList[_]].get("msg").asInstanceOf[String]
        // TODO? get the trace?
      }
    } catch {
      case NonFatal(e) => // nothing for now
    }
    if (reason == null) {
      val msg = new StringBuilder()
      msg.append(response.getStatusText())
      msg.append("\n\n")
      msg.append("request: " + url)
      reason = msg.toString()
    }
    reason
  }

}

trait TypedAsyncSolrClient[F[_], ASC <: AsyncSolrClient[F]] {

  protected implicit def futureFactory: FutureFactory[F]

  protected def build(loadBalancer: LoadBalancer,
                      httpClient: AsyncHttpClient,
                      shutdownHttpClient: Boolean,
                      requestInterceptor: Option[RequestInterceptor],
                      requestWriter: RequestWriter,
                      responseParser: ResponseParser,
                      metrics: Metrics,
                      serverStateObservation: Option[ServerStateObservation[F]],
                      retryPolicy: RetryPolicy): ASC

  def builder(baseUrl: String) = new Builder[F, ASC](new SingleServerLB(baseUrl), build _)
  def builder(loadBalancer: LoadBalancer) = new Builder[F, ASC](loadBalancer, build _)

}

trait AsyncSolrClientAware[F[_]] {

  /**
    * On creation of AsyncSolrClient this method is invoked with the created instance if the
    * concrete component is "supported", right now this are SolrServers and LoadBalancer.
    * Subclasses can override this method to get access to the solr client.
    */
  def setAsyncSolrClient(solr: AsyncSolrClient[F]): Unit = {
    // empty default
  }

}

/**
  * Subclass of SolrException that allows us to capture an arbitrary HTTP
  * status code that may have been returned by the remote server or a
  * proxy along the way.
  *
  * @param code Arbitrary HTTP status code
  * @param msg Exception Message
  * @param th Throwable to wrap with this Exception
  */
class RemoteSolrException(code: Int, msg: String, th: Throwable) extends SolrException(code, msg, th)
