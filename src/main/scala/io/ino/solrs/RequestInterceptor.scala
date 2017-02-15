package io.ino.solrs

import org.apache.solr.client.solrj.SolrRequest
import org.apache.solr.client.solrj.SolrResponse
import io.ino.solrs.future.Future

/**
 * Clients can intercept requests.
 */
trait RequestInterceptor {

  /**
   * Intercept the given function, invoke the function with the given arguments and return the result.
   * @param f the function to intercept
   * @param solrServer the SolrServer that's used to run the request
   * @param r the request to send to solr.
   * @return the solr response.
   */
  def interceptRequest[T <: SolrResponse](f: (SolrServer, SolrRequest[_ <: T]) => Future[T])
                                         (solrServer: SolrServer, r: SolrRequest[_ <: T]): Future[T]

}
