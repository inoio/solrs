package io.ino.solrs

import java.util.concurrent.CompletableFuture

import org.asynchttpclient.AsyncHttpClient
import io.ino.solrs.AsyncSolrClient.Builder
import io.ino.solrs.future.{JavaFutureFactory, FutureFactory}
import org.apache.solr.client.solrj.response.QueryResponse
import org.apache.solr.client.solrj.{SolrQuery, ResponseParser}
import org.apache.solr.client.solrj.impl.BinaryResponseParser

import scala.language.higherKinds

/**
 * Java API: Async, non-blocking Solr Server that just allows to `query(SolrQuery)`.
 * The usage shall be similar to the <a href="https://wiki.apache.org/solr/Solrj">solrj SolrServer</a>,
 * so query returns a [[java.util.concurrent.CompletableFuture CompletableFuture]] of a
 * [[org.apache.solr.client.solrj.response.QueryResponse QueryResponse]].
 *
 * Example usage:
 * {{{
 * JavaAsyncSolrClient solr = JavaAsyncSolrClient.create("http://localhost:" + solrRunner.port + "/solr/collection1");
 * CompletableFuture<QueryResponse> response = solr.query(new SolrQuery("*:*"));
 * response.thenAccept(r -> System.out.println("found "+ r.getResults().getNumFound() +" docs"));
 * }}}
 */
class JavaAsyncSolrClient(override private[solrs] val loadBalancer: LoadBalancer,
                          httpClient: AsyncHttpClient,
                          shutdownHttpClient: Boolean,
                          requestInterceptor: Option[RequestInterceptor] = None,
                          responseParser: ResponseParser = new BinaryResponseParser,
                          metrics: Metrics = NoopMetrics,
                          serverStateObservation: Option[ServerStateObservation[CompletableFuture]] = None,
                          retryPolicy: RetryPolicy = RetryPolicy.TryOnce)
  extends AsyncSolrClient[CompletableFuture](loadBalancer, httpClient, shutdownHttpClient, requestInterceptor, responseParser, metrics, serverStateObservation, retryPolicy)(JavaFutureFactory) {

  /**
   * @inheritdoc
   */
  override def query(q: SolrQuery): CompletableFuture[QueryResponse] = super.query(q)

  /**
   * @inheritdoc
   */
  override def queryPreferred(q: SolrQuery, preferred: Option[SolrServer]): CompletableFuture[(QueryResponse, SolrServer)] = super.queryPreferred(q, preferred)

}

object JavaAsyncSolrClient extends TypedAsyncSolrClient[CompletableFuture, JavaAsyncSolrClient] {

  private implicit val ff = JavaFutureFactory

  override protected def futureFactory: FutureFactory[CompletableFuture] = JavaFutureFactory

  def create(url: String): JavaAsyncSolrClient = builder(url).build

  override def builder(url: String): Builder[CompletableFuture, JavaAsyncSolrClient] = new Builder(url, build _)
  override def builder(loadBalancer: LoadBalancer): Builder[CompletableFuture, JavaAsyncSolrClient] = new Builder(loadBalancer, build _)

  override protected def build(loadBalancer: LoadBalancer,
                               httpClient: AsyncHttpClient,
                               shutdownHttpClient: Boolean,
                               requestInterceptor: Option[RequestInterceptor],
                               responseParser: ResponseParser,
                               metrics: Metrics,
                               serverStateObservation: Option[ServerStateObservation[CompletableFuture]],
                               retryPolicy: RetryPolicy): JavaAsyncSolrClient =
    new JavaAsyncSolrClient(
      loadBalancer,
      httpClient,
      shutdownHttpClient,
      requestInterceptor,
      responseParser,
      metrics,
      serverStateObservation,
      retryPolicy)
}