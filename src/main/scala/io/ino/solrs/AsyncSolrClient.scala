package io.ino.solrs

import java.io.IOException

import akka.actor.ActorSystem
import io.ino.concurrent.Execution
import io.ino.solrs.RetryDecision.Result
import org.apache.solr.client.solrj.ResponseParser
import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.SolrServerException
import org.apache.solr.client.solrj.response.QueryResponse

import org.apache.solr.client.solrj.util.ClientUtils
import org.apache.solr.common.params.{CommonParams, ModifiableSolrParams}
import org.apache.solr.common.util.NamedList
import org.slf4j.LoggerFactory

import com.ning.http.client.{AsyncCompletionHandler, AsyncHttpClient, Response}

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.control.NonFatal
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try
import org.apache.solr.common.SolrException
import org.apache.solr.client.solrj.impl.BinaryResponseParser
import java.util.Locale
import org.apache.commons.io.IOUtils
import HttpUtils._

object AsyncSolrClient {

  def apply(baseUrl: String) = new Builder(new SingleServerLB(baseUrl)).build
  def apply(loadBalancer: LoadBalancer) = new Builder(loadBalancer).build

  object Builder {
    def apply(baseUrl: String) = new Builder(baseUrl)
    def apply(loadBalancer: LoadBalancer) = new Builder(loadBalancer)
  }

  case class Builder private (loadBalancer: LoadBalancer,
                              httpClient: Option[AsyncHttpClient],
                              shutdownHttpClient: Boolean,
                              requestInterceptor: Option[RequestInterceptor] = None,
                              responseParser: Option[ResponseParser] = None,
                              metrics: Option[Metrics] = None,
                              serverStateObservation: Option[ServerStateObservation] = None,
                              retryPolicy: RetryPolicy = RetryPolicy.TryOnce) {

    def this(loadBalancer: LoadBalancer) = this(loadBalancer, None, true)
    def this(baseUrl: String) = this(new SingleServerLB(baseUrl))

    def withHttpClient(httpClient: AsyncHttpClient): Builder = {
      copy(httpClient = Some(httpClient), shutdownHttpClient = false)
    }

    def withRequestInterceptor(requestInterceptor: RequestInterceptor): Builder = {
      copy(requestInterceptor = Some(requestInterceptor))
    }

    def withResponseParser(responseParser: ResponseParser): Builder = {
      copy(responseParser = Some(responseParser))
    }

    def withMetrics(metrics: Metrics): Builder = {
      copy(metrics = Some(metrics))
    }

    /**
     * Configures server state observation using the given observer and the provided interval.
     */
    def withServerStateObservation(serverStateObserver: ServerStateObserver,
                              checkInterval: FiniteDuration,
                              actorSystem: ActorSystem)(implicit ec: ExecutionContext): Builder = {
      copy(serverStateObservation = Some(ServerStateObservation(serverStateObserver, checkInterval, actorSystem, ec)))
    }

    /**
     * Configure the retry policy to apply for failed requests.
     */
    def withRetryPolicy(retryPolicy: RetryPolicy): Builder = {
      copy(retryPolicy = retryPolicy)
    }

    protected def createHttpClient: AsyncHttpClient = new AsyncHttpClient()

    protected def createResponseParser: ResponseParser = new BinaryResponseParser

    protected def createMetrics: Metrics = NoopMetrics

    // the load balancer and others might need to access this instance, extracted as protected method to be overridable from tests
    protected def setOnAsyncSolrClientAwares(solr: AsyncSolrClient): Unit = {
      // the solr servers should be able to probe servers before the load balancer gets the handle...
      // it's also set here (instead of letting the LoadBalancer pass the solrs instance to SolrServers),
      // so that a LoadBalancer subclass cannot not forget to invoke super
      loadBalancer.solrServers.setAsyncSolrClient(solr)
      loadBalancer.setAsyncSolrClient(solr)
    }

    def build: AsyncSolrClient = {
      val res = new AsyncSolrClient(
        loadBalancer,
        httpClient.getOrElse(createHttpClient),
        shutdownHttpClient,
        requestInterceptor,
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
 * Async, non-blocking Solr Server that just allows to {@link #query(SolrQuery)}.
 * The usage shall be similar to the <a href="https://wiki.apache.org/solr/Solrj">solrj SolrServer</a>,
 * so query returns a future of a {@link QueryResponse}.
 *
 * @author <a href="martin.grotzke@inoio.de">Martin Grotzke</a>
 */
class AsyncSolrClient private (val loadBalancer: LoadBalancer,
                               val httpClient: AsyncHttpClient,
                               shutdownHttpClient: Boolean,
                               requestInterceptor: Option[RequestInterceptor] = None,
                               responseParser: ResponseParser = new BinaryResponseParser,
                               val metrics: Metrics = NoopMetrics,
                               serverStateObservation: Option[ServerStateObservation] = None,
                               retryPolicy: RetryPolicy = RetryPolicy.TryOnce) {

  private val UTF_8 = "UTF-8"
  private val DEFAULT_PATH = "/select"

  /**
   * User-Agent String.
   */
  val AGENT = "Solr[" + classOf[AsyncSolrClient].getName() + "] 1.0"

  private val logger = LoggerFactory.getLogger(getClass())

  private val cancellableObservation = serverStateObservation.map { observation =>
    observation.actorSystem.scheduler.schedule(0 seconds, observation.checkInterval) {
      observation.serverStateObserver.checkServerStatus()(observation.ec)
    }(observation.ec)
  }

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

  /**
   * Closes the http client (asynchronously) if it was not provided but created by this class.
   */
  def shutdown() = {
    cancellableObservation.foreach(_.cancel())
    if(shutdownHttpClient) {
      httpClient.closeAsynchronously()
    }
    loadBalancer.shutdown()
  }

  /**
   * Performs a query to a solr server.
   * @param q the query to send to solr.
   * @return
   */
  def query(q: SolrQuery): Future[QueryResponse] = {
    loadBalanceQuery(QueryContext(q)).map(_._1)(Execution.sameThreadContext)
  }

  /**
   * Performs a query to a solr server taking the preferred server into account if provided.
   * @param q the query to send to the solr server.
   * @param preferred the server that should be preferred to process the query. Specific [[io.ino.solrs.LoadBalancer LoadBalancer]]
   *                  implementations have to support this and might add their own semantics.
   * @return the response and the server that handled the query.
   */
  def queryPreferred(q: SolrQuery, preferred: Option[SolrServer]): Future[(QueryResponse, SolrServer)] = {
    loadBalanceQuery(QueryContext(q, preferred))
  }

  private def loadBalanceQuery(queryContext: QueryContext): Future[(QueryResponse, SolrServer)] = {
    loadBalancer.solrServer(queryContext.q, queryContext.preferred) match {
      case Some(solrServer) => queryWithRetries(solrServer, queryContext)
      case None =>
        val msg =
          if(queryContext.failedRequests.isEmpty) "No solr server available."
          else s"No next solr server available. These requests failed:\n- ${queryContext.failedRequests.mkString("\n- ")}"
        Future.failed(new SolrServerException(msg))
    }
  }

  private def queryWithRetries(server: SolrServer, queryContext: QueryContext): Future[(QueryResponse, SolrServer)] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    val start = System.currentTimeMillis()
    query(server, queryContext.q)
      .map(_ -> server)(Execution.sameThreadContext)
      .recoverWith { case NonFatal(e) =>
        val updatedContext = queryContext.failedRequest(server, (System.currentTimeMillis() - start) millis, e)
        retryPolicy.shouldRetry(e, server, updatedContext, loadBalancer) match {
          case RetryServer(s) =>
            logger.warn(s"Query failed for server $server, trying next server $s. Exception was: $e")
            queryWithRetries(s, updatedContext)
          case StandardRetryDecision(Result.Retry) =>
            logger.warn(s"Query failed for server $server, trying to get another server from loadBalancer for retry. Exception was: $e")
            loadBalanceQuery(updatedContext)
          case StandardRetryDecision(Result.Fail) =>
            logger.warn(s"Query failed for server $server, not retrying. Exception was: $e", e)
            // Wrap SolrException with solrs RemoteSolrException
            val ex = if(e.isInstanceOf[SolrException]) new RemoteSolrException(500, e.getMessage, e) else e
            Future.failed(ex)
        }
      }
  }

  private def query(solrServer: SolrServer, q: SolrQuery): Future[QueryResponse] = {
    val monitoredQuery = loadBalancer.interceptQuery(doQuery) _
    requestInterceptor.map(ri =>
      ri.interceptQuery(monitoredQuery)(solrServer, q)
    ).getOrElse(monitoredQuery(solrServer, q))
  }

  private[solrs] def doQuery(solrServer: SolrServer, q: SolrQuery): Future[QueryResponse] = {

    val wparams = new ModifiableSolrParams(q)
    if (responseParser != null) {
      wparams.set(CommonParams.WT, responseParser.getWriterType())
      wparams.set(CommonParams.VERSION, responseParser.getVersion())
    }

    implicit val s = solrServer

    val promise = scala.concurrent.promise[QueryResponse]
    val startTime = System.currentTimeMillis()

    val url = solrServer.baseUrl + getPath(q) + ClientUtils.toQueryString(wparams, false)
    val request = httpClient.prepareGet(url).addHeader("User-Agent", AGENT).build()
    httpClient.executeRequest(request, new AsyncCompletionHandler[Response]() {
      override def onCompleted(response: Response): Response = {
        val tryQueryResponse = Try(toQueryResponse(response, url, startTime))
        promise.complete(tryQueryResponse)
        response
      }
      override def onThrowable(t: Throwable) {
        metrics.countException
        promise.failure(t)
      }
    })

    promise.future
  }

  protected def getPath(query: SolrQuery): String = {
    val qt = query.get(CommonParams.QT)
    if (qt != null && qt.startsWith("/")) {
      return qt
    }
    return DEFAULT_PATH
  }

  @throws[RemoteSolrException]
  protected def toQueryResponse(response: Response, url: String, startTime: Long)(implicit server: SolrServer): QueryResponse = {
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

    val res = new QueryResponse(rsp, null)
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

trait AsyncSolrClientAware {

  /**
   * On creation of AsyncSolrClient this method is invoked with the created instance if the
   * concrete component is "supported", right now this are SolrServers and LoadBalancer.
   * Subclasses can override this method to get access to the solr client.
   */
  def setAsyncSolrClient(solr: AsyncSolrClient): Unit = {
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