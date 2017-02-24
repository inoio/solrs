package io.ino.solrs

import org.apache.solr.client.solrj.request.QueryRequest
import org.apache.solr.common.params.SolrParams
import org.hamcrest.CoreMatchers.equalTo
import org.hamcrest.Matchers.hasProperty
import org.mockito.Matchers.argThat

object SolrMatchers {
  def hasQuery(query: SolrParams): QueryRequest = argThat(hasProperty("params", equalTo(query)))
}
