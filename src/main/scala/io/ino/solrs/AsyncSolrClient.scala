package io.ino.solrs

import java.io.IOException

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
import scala.util.{Failure, Success, Try}
import org.apache.solr.common.SolrException
import org.apache.solr.client.solrj.impl.BinaryResponseParser
import java.util.Locale
import org.apache.commons.io.IOUtils
import HttpUtils._

/**
 * Async, non-blocking Solr Server that just allows to {@link #query(SolrQuery)}.
 * The usage shall be similar to the <a href="https://wiki.apache.org/solr/Solrj">solrj SolrServer</a>,
 * so query returns a future of a {@link QueryResponse}.
 *
 * @author <a href="martin.grotzke@inoio.de">Martin Grotzke</a>
 */
class AsyncSolrClient(val baseUrl: String,
                      val httpClient: AsyncHttpClient = new AsyncHttpClient(),
                      responseParser: ResponseParser = new BinaryResponseParser,
                      val metrics: Metrics = NoopMetrics) {

  private var shutdownExecutor = false
  private val UTF_8 = "UTF-8"
  private val DEFAULT_PATH = "/select"

  /**
   * User-Agent String.
   */
  val AGENT = "Solr[" + classOf[AsyncSolrClient].getName() + "] 1.0"

  private val logger = LoggerFactory.getLogger(getClass())

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
          promise.complete(tryTransformResponse(toQueryResponse(response, url, startTime), transformResponse))
          response
        }
        override def onThrowable(t: Throwable) {
          metrics.countException
          promise.failure(t)
        }
      })

      promise.future
    } catch {
      case e: IOException =>
        metrics.countIOException
        throw new SolrServerException("IOException occured when talking to server at: " + baseUrl, e)
    }
  }

  private def tryTransformResponse[T](response: QueryResponse, transformResponse: (QueryResponse) => T): Try[T] with Product with Serializable = {
    try {
      Success(transformResponse(response))
    } catch {
      case NonFatal(e) =>
        metrics.countTransformResponseException
        Failure(e)
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
  private def validateResponse(response: Response, responseParser: ResponseParser) {
    validateMimeType(responseParser.getContentType, response)

    val httpStatus = response.getStatusCode
    if(httpStatus >= 400) {
      metrics.countRemoteException
      throw new RemoteSolrException(httpStatus, s"Server at $baseUrl returned non ok status:$httpStatus, message:${response.getStatusText}", null)
    }
  }

  def validateMimeType(expectedContentType: String, response: Response) {
    if (expectedContentType != null) {
      val expectedMimeType = getMimeType(expectedContentType).map(_.toLowerCase(Locale.ROOT)).getOrElse("")
      val actualMimeType = getMimeType(response.getContentType).map(_.toLowerCase(Locale.ROOT)).getOrElse("")
      if (expectedMimeType != actualMimeType) {
        var msg = s"Expected mime type [$expectedMimeType] but got [$actualMimeType]."
        var encoding = response.getHeader("Content-Encoding")
        if (encoding == null) {
          encoding = "UTF-8"
        }
        try {
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