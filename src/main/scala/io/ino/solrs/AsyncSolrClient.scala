package io.ino.solrs

import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

import org.apache.solr.client.solrj.ResponseParser
import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.SolrServerException
import org.apache.solr.client.solrj.response.QueryResponse

import org.apache.solr.client.solrj.util.ClientUtils
import org.apache.solr.common.params.CommonParams
import org.apache.solr.common.params.ModifiableSolrParams
import org.apache.solr.common.util.NamedList
import org.slf4j.LoggerFactory

import com.ning.http.client.{AsyncCompletionHandler, AsyncHttpClient, Response}

import scala.util.control.NonFatal
import scala.concurrent.Future
import scala.util.Try
import org.apache.solr.common.SolrException

/**
 * Async, non-blocking Solr Server that just allows to {@link #query(SolrQuery)}.
 * The usage shall be similar to the <a href="https://wiki.apache.org/solr/Solrj">solrj SolrServer</a>,
 * so query returns a future of a {@link QueryResponse}.
 *
 * @author <a href="martin.grotzke@inoio.de">Martin Grotzke</a>
 */
class AsyncSolrClient(val baseUrl: String, val httpClient: AsyncHttpClient, responseParser: ResponseParser, val responseParserExecutor: ExecutorService) {

  def this(baseUrl: String, httpClient: AsyncHttpClient, responseParser: ResponseParser) = {
    this(baseUrl, httpClient, responseParser, Executors.newFixedThreadPool(
      Math.max(Runtime.getRuntime().availableProcessors() - 1, 1)))
    shutdownExecutor = true
  }

  private var shutdownExecutor = false
  private val UTF_8 = "UTF-8"
  private val DEFAULT_PATH = "/select"

  /**
   * User-Agent String.
   */
  val AGENT = "Solr[" + classOf[AsyncSolrClient].getName() + "] 1.0"

  private val logger = LoggerFactory.getLogger(getClass())

  def shutdown(): Unit = {
    if (shutdownExecutor) {
      try {
        responseParserExecutor.shutdownNow()
      } catch {
        case NonFatal(e) => logger.warn(e.getMessage(), e)
      }
    }
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

  @throws[SolrServerException]
  def query(q: SolrQuery): Future[QueryResponse] = {
    query(q, identity)
  }

  @throws[SolrServerException]
  def query[T](q: SolrQuery, transformResponse: QueryResponse => T): Future[T] = {

    val wparams = new ModifiableSolrParams(q)
    if (responseParser != null) {
      wparams.set(CommonParams.WT, responseParser.getWriterType())
      wparams.set(CommonParams.VERSION, responseParser.getVersion())
    }

    val url = baseUrl + getPath(q) + ClientUtils.toQueryString(wparams, false)
    try {
      val promise = scala.concurrent.promise[T]
      val startTime = System.currentTimeMillis()

      val request = httpClient.prepareGet(url).addHeader("User-Agent", AGENT).build()
      httpClient.executeRequest(request, new AsyncCompletionHandler[Response]() {
        override def onCompleted(response: Response): Response = {
          promise.complete(Try(transformResponse(toQueryResponse(response, url, startTime))))
          response
        }
        override def onThrowable(t: Throwable) {
          promise.failure(t)
        }
      })

      promise.future
    } catch {
      case e: IOException => throw new SolrServerException("IOException occured when talking to server at: " + baseUrl, e)
    }
  }

  protected def getPath(query: SolrQuery): String = {
    val qt = query.get(CommonParams.QT)
    if (qt != null && qt.startsWith("/")) {
      return qt
    }
    return DEFAULT_PATH
  }

  protected def toQueryResponse(response: Response, url: String, startTime: Long): QueryResponse = {
    var rsp: NamedList[Object] = null

    val httpStatus = response.getStatusCode()

    try {
      rsp = responseParser.processResponse(response.getResponseBodyAsStream(), UTF_8)
    } catch {
      case NonFatal(e) => throw new RemoteSolrException(httpStatus, e.getMessage(), e)
    }

    if (httpStatus != 200) {
      val reason = getErrorReason(url, rsp, response)
      throw new RemoteSolrException(httpStatus, reason, null)
    }

    val res = new QueryResponse(rsp, null)
    res.setElapsedTime(System.currentTimeMillis() - startTime)
    res
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