package io.ino.solrs

import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.response.QueryResponse

import io.ino.solrs.future.Future

/**
 * Clients can intercept requests.
 */
trait RequestInterceptor {

  /**
   * Intercept the given function, invoke the function with the given arguments and return the result.
   * @param f the function to intercept
   * @param solrServer the SolrServer that's used to run the query
   * @param q the query to send to solr.
   * @return the query response.
   */
  def interceptQuery(f: (SolrServer, SolrQuery) => Future[(Option[QueryResponse], Option[Throwable])])
                    (solrServer: SolrServer, q: SolrQuery): Future[(Option[QueryResponse], Option[Throwable])]

}
