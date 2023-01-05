package io.ino.solrs

import scala.annotation.tailrec
import scala.util.Failure
import scala.util.Success

/**
 * Specifies a policy for retrying request failures.
 */
abstract class RetryPolicy {
  /**
   * Determines whether the framework should retry a request for the given
   * exception, the failed server and the request context (provides information about previously failed
   * requests, e.g. with previously failed servers).
   *
   * @param e The exception that caused the method to fail
   * @param server The server that was used for the failed request
   * @param requestContext The context of the request initiated by the client, e.g. provides the servers already tried
   * @param lb The configured load balancer 
   */
  def shouldRetry(e: Throwable, server: SolrServer, requestContext: RequestContext[_], lb: LoadBalancer): RetryDecision
}

/**
 * A retry decision to adopt on a failed request.
 */
sealed trait RetryDecision {
  val result: RetryDecision.Result
}

import RetryDecision._
case class StandardRetryDecision(override val result: Result/*, delayTimeMillis: Long = 0*/) extends RetryDecision
case class RetryServer(server: SolrServer) extends RetryDecision {
  override val result: Result = Result.Retry
}

object RetryDecision {

  final val Fail: StandardRetryDecision = StandardRetryDecision(Result.Fail)
  final val Retry: StandardRetryDecision = StandardRetryDecision(Result.Retry)

  sealed trait Result
  object Result {
    case object Fail extends Result
    case object Retry extends Result
  }
}

/**
 * Predefined request retry policies.
 */
object RetryPolicy {

  /**
   * Don't retry, propagate the first failure.
   */
  val TryOnce: RetryPolicy = new RetryPolicy {
    override def shouldRetry(e: Throwable, server: SolrServer, requestContext: RequestContext[_], lb: LoadBalancer): StandardRetryDecision = RetryDecision.Fail
  }

  /**
   * Try all servers provided by SolrServers (fetching the next server from the LoadBalancer).
   * When requests for all servers failed, the last failure is propagated to the client.
   */
  val TryAvailableServers: RetryPolicy = new RetryPolicy {

    override def shouldRetry(e: Throwable, server: SolrServer, requestContext: RequestContext[_], lb: LoadBalancer): RetryDecision = {

      val countServers = lb.solrServers.all.length
      val preferred = requestContext.preferred.flatMap(p =>
        if(requestContext.triedServers.contains(p)) None else requestContext.preferred
      )

      @tailrec
      def findAvailable(round: Int): Option[SolrServer] = {
        if(round >= countServers) {
          None
        } else {
          val maybeServer = lb.solrServer(requestContext.r, preferred)
          maybeServer match {
            case Failure(_) => None
            case Success(s) if s == server || requestContext.triedServers.contains(s) => findAvailable(round + 1)
            case Success(s) => Some(s)
          }
        }
      }

      findAvailable(0) match {
        case Some(s) => RetryServer(s)
        case None => RetryDecision.Fail
      }
    }
  }

  /**
   * Retries the given number of times.
   */
  def AtMost(times: Int): RetryPolicy = new RetryPolicy {
    override def shouldRetry(e: Throwable, server: SolrServer, requestContext: RequestContext[_], lb: LoadBalancer): RetryDecision = {
      if(requestContext.triedServers.length < times) RetryDecision.Retry
      else RetryDecision.Fail
    }
  }

}