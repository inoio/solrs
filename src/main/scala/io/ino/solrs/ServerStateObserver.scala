package io.ino.solrs

import akka.actor.ActorSystem
import com.ning.http.client.{RequestBuilder, Response, AsyncCompletionHandler, AsyncHttpClient}
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}
import scala.xml.XML

/**
 * Monitoring of solr server state (enabled/disabled/dead etc.)
 */
trait ServerStateObserver {
  def checkServerStatus()(implicit ec: ExecutionContext): Future[Unit]
}

/**
 * Configuration for scheduled server state observation.
 * @param serverStateObserver the observer that checks server state
 * @param checkInterval the interval to check server state
 * @param actorSystem used for scheduling
 * @param ec used for running the scheduled observation
 */
case class ServerStateObservation(serverStateObserver: ServerStateObserver,
                                  checkInterval: FiniteDuration,
                                  actorSystem: ActorSystem,
                                  ec: ExecutionContext)


/**
 * A ServerStateObserver that uses the ping status to enable/disable SolrServers.
 * To use this in solrconfig.xml the PingRequestHandler must be configured with the
 * healthcheckFile, e.g.:
 * <code><pre>
 * &lt;str name="healthcheckFile"&gt;server-enabled.txt&lt;/str&gt;
 * </pre></code>
 */
class PingStatusObserver(solrServers: Seq[SolrServer], httpClient: AsyncHttpClient) extends ServerStateObserver {

  private val logger = LoggerFactory.getLogger(getClass())

  override def checkServerStatus()(implicit ec: ExecutionContext): Future[Unit] = {
    val futures = solrServers.map { server =>
      val url = server.baseUrl + "/admin/ping?action=status"
      val promise = scala.concurrent.promise[Unit]
      httpClient.prepareGet(url).execute(new AsyncCompletionHandler[Response]() {
        override def onCompleted(response: Response): Response = {
          updateServerStatus(server, response, url)
          promise.complete(Success(server.status))
          response
        }
        override def onThrowable(t: Throwable): Unit = {
          if(server.status != Failed) {
            logger.error(s"An error occurred when trying to get ping status from $url, changing status to Failed.", t)
            server.status = Failed
          }
          promise.failure(t)
        }
      })
      promise.future
    }
    Future.sequence(futures).map(_ => Unit)
  }

  private def updateServerStatus(server: SolrServer, response: Response, url: String) {
    if (response.getStatusCode != 200) {
      logger.warn(s"Got ping response status != 200 (${response.getStatusCode}) from $url, with response '${new String(response.getResponseBodyAsBytes)}'")
      server.status = Failed
    } else {
      val xml = XML.load(response.getResponseBodyAsStream)
      (xml \\ "response" \ "str").find(node => (node \ "@name").text == "status") match {
        case None =>
          if(server.status != Failed) {
            logger.warn(s"Could not find status in ping response from $url, changing status to Failed. Response:\n$xml")
            server.status = Failed
          }
        case Some(statusNode) =>
          val status = if (statusNode.text == "enabled") Enabled else Disabled
          if(server.status != status) {
            logger.info(s"Changing status for server ${server.baseUrl} from ${server.status} to $status.")
            server.status = status
          }
      }
    }
  }
}