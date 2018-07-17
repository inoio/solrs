package io.ino.solrs

import org.apache.solr.client.solrj.request.{QueryRequest, SolrPing, UpdateRequest}
import org.apache.solr.client.solrj.response.{QueryResponse, SimpleSolrResponse, SolrPingResponse, UpdateResponse}
import org.apache.solr.client.solrj.{SolrRequest, SolrResponse}

trait SolrResponseFactory[T <: SolrResponse] {
  def createResponse(request: SolrRequest[_ <: T]): T
}

object SolrResponseFactory {
  def apply[T <: SolrResponse](implicit factory: SolrResponseFactory[T]): SolrResponseFactory[T] = factory

  def instance[T <: SolrResponse](func: SolrRequest[_ <: T] => T): SolrResponseFactory[T] =
    new SolrResponseFactory[T] {
      override def createResponse(request: SolrRequest[_ <: T]): T = func(request)
    }

  implicit val queryResponseFactory: SolrResponseFactory[QueryResponse] =
    instance(_ => new QueryResponse(null))

  implicit val simpleSolrResponseFactory: SolrResponseFactory[SimpleSolrResponse] =
    instance(_ => new SimpleSolrResponse)

  implicit val updateResponseFactory: SolrResponseFactory[UpdateResponse] =
    instance(_ => new UpdateResponse)

  implicit val pingResponseFactory: SolrResponseFactory[SolrPingResponse] =
    instance(_ => new SolrPingResponse)

  implicit val dynamicResponseFactory: SolrResponseFactory[SolrResponse] =
    instance {
      case r: QueryRequest => SolrResponseFactory[QueryResponse].createResponse(r)
      case r: UpdateRequest => SolrResponseFactory[UpdateResponse].createResponse(r)
      case r: SolrPing => SolrResponseFactory[SolrPingResponse].createResponse(r)
    }
}


