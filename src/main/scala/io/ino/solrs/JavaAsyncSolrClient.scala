package io.ino.solrs

import java.util
import java.util.Optional
import java.util.concurrent.CompletionStage

import io.ino.solrs.AsyncSolrClient.Builder
import io.ino.solrs.future.FutureFactory
import io.ino.solrs.future.JavaFutureFactory
import org.apache.solr.client.solrj.SolrRequest.METHOD
import org.apache.solr.client.solrj._
import org.apache.solr.client.solrj.impl.BinaryRequestWriter
import org.apache.solr.client.solrj.impl.BinaryResponseParser
import org.apache.solr.client.solrj.request.RequestWriter
import org.apache.solr.client.solrj.response.QueryResponse
import org.apache.solr.client.solrj.response.SolrPingResponse
import org.apache.solr.client.solrj.response.UpdateResponse
import org.apache.solr.common.params.SolrParams
import org.apache.solr.common.SolrDocument
import org.apache.solr.common.SolrDocumentList
import org.apache.solr.common.SolrInputDocument
import org.asynchttpclient.AsyncHttpClient

import scala.collection.JavaConverters._
import scala.compat.java8.FunctionConverters._
import scala.compat.java8.OptionConverters._
import scala.language.higherKinds

/**
 * Java API: Async, non-blocking Solr Server that allows to make requests to Solr.
 * The usage shall be similar to the <a href="https://wiki.apache.org/solr/Solrj">solrj SolrServer</a>,
 * so request returns a [[java.util.concurrent.CompletionStage CompletionStage]] of a
 * [[org.apache.solr.client.solrj.SolrResponse SolrResponse]].
 *
 * Example usage:
 * {{{
 * JavaAsyncSolrClient solr = JavaAsyncSolrClient.create("http://localhost:" + solrRunner.port + "/solr/collection1");
 * CompletionStage<QueryResponse> response = solr.query(new SolrQuery("*:*"));
 * response.thenAccept(r -> System.out.println("found "+ r.getResults().getNumFound() +" docs"));
 * }}}
 */
class JavaAsyncSolrClient(override private[solrs] val loadBalancer: LoadBalancer,
                          httpClient: AsyncHttpClient,
                          shutdownHttpClient: Boolean,
                          requestInterceptor: Option[RequestInterceptor] = None,
                          requestWriter: RequestWriter = new BinaryRequestWriter,
                          responseParser: ResponseParser = new BinaryResponseParser,
                          metrics: Metrics = NoopMetrics,
                          serverStateObservation: Option[ServerStateObservation[CompletionStage]] = None,
                          retryPolicy: RetryPolicy = RetryPolicy.TryOnce)
  extends AsyncSolrClient[CompletionStage](loadBalancer, httpClient, shutdownHttpClient, requestInterceptor, requestWriter, responseParser, metrics, serverStateObservation, retryPolicy)(JavaFutureFactory) {

  /**
    * Performs a request to a solr server.
    *
    * @param r the request to send to solr.
    * @return
    */
  override def execute[T <: SolrResponse : SolrResponseFactory](r: SolrRequest[_ <: T]): CompletionStage[T] = super.execute(r)

  /**
    * Performs a request to a solr server taking the preferred server into account if provided.
    *
    * @param r         the request to send to the solr server.
    * @param preferred the server that should be preferred to process the request. Specific [[io.ino.solrs.LoadBalancer LoadBalancer]]
    *                  implementations have to support this and might add their own semantics.
    * @return the response and the server that handled the request.
    */
  override def executePreferred[T <: SolrResponse : SolrResponseFactory](r: SolrRequest[_ <: T], preferred: Option[SolrServer]): CompletionStage[(T, SolrServer)] =
    super.executePreferred(r, preferred)

  /**
    * Adds a collection of documents, specifying max time before they become committed
    *
    * @param docs the collection of documents
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} from the server
    */
  def addDocs(docs: util.Collection[SolrInputDocument]): CompletionStage[UpdateResponse] =
    super.addDocs(docs = docs.asScala)

  /**
    * Adds a collection of documents, specifying max time before they become committed
    *
    * @param docs           the collection of documents
    * @param commitWithinMs max time (in ms) before a commit will happen
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} from the server
    */
  def addDocs(docs: util.Collection[SolrInputDocument], commitWithinMs: Int): CompletionStage[UpdateResponse] =
    super.addDocs(docs = docs.asScala, commitWithinMs = commitWithinMs)

  /**
    * Adds a collection of documents, specifying max time before they become committed
    *
    * @param collection the Solr collection to add documents to
    * @param docs       the collection of documents
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} from the server
    */
  def addDocs(collection: String, docs: util.Collection[SolrInputDocument]): CompletionStage[UpdateResponse] =
    super.addDocs(Option(collection), docs.asScala)

  /**
    * Adds a collection of documents, specifying max time before they become committed
    *
    * @param collection     the Solr collection to add documents to
    * @param docs           the collection of documents
    * @param commitWithinMs max time (in ms) before a commit will happen
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} from the server
    */
  def addDocs(collection: String, docs: util.Collection[SolrInputDocument], commitWithinMs: Int): CompletionStage[UpdateResponse] =
    super.addDocs(Option(collection), docs.asScala, commitWithinMs)

  /**
    * Adds the documents supplied by the given iterator.
    *
    * @param docIterator the iterator which returns SolrInputDocument instances
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} from the server
    */
  def addDocs(docIterator: util.Iterator[SolrInputDocument]): CompletionStage[UpdateResponse] =
    super.addDocs(docIterator.asScala)

  /**
    * Adds the documents supplied by the given iterator.
    *
    * @param collection  the Solr collection to add documents to
    * @param docIterator the iterator which returns SolrInputDocument instances
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} from the server
    */
  def addDocs(collection: String, docIterator: util.Iterator[SolrInputDocument]): CompletionStage[UpdateResponse] =
    super.addDocs(collection, docIterator.asScala)

  /**
    * Adds a single document specifying max time before it becomes committed
    *
    * @param doc the input document
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} from the server
    */
  def addDoc(doc: SolrInputDocument): CompletionStage[UpdateResponse] = super.addDoc(doc = doc)

  /**
    * Adds a single document specifying max time before it becomes committed
    *
    * @param doc            the input document
    * @param commitWithinMs max time (in ms) before a commit will happen
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} from the server
    */
  def addDoc(doc: SolrInputDocument, commitWithinMs: Int): CompletionStage[UpdateResponse] =
    super.addDoc(doc = doc, commitWithinMs = commitWithinMs)

  /**
    * Adds a single document specifying max time before it becomes committed
    *
    * @param collection the Solr collection to add the document to
    * @param doc        the input document
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} from the server
    */
  def addDoc(collection: String, doc: SolrInputDocument): CompletionStage[UpdateResponse] =
    super.addDoc(Option(collection), doc)

  /**
    * Adds a single document specifying max time before it becomes committed
    *
    * @param collection     the Solr collection to add the document to
    * @param doc            the input document
    * @param commitWithinMs max time (in ms) before a commit will happen
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} from the server
    */
  def addDoc(collection: String, doc: SolrInputDocument, commitWithinMs: Int): CompletionStage[UpdateResponse] =
    super.addDoc(Option(collection), doc, commitWithinMs)

  /**
    * Adds a single bean specifying max time before it becomes committed
    * The bean is converted to a {@link SolrInputDocument} by the client's
    * {@link org.apache.solr.client.solrj.beans.DocumentObjectBinder}
    *
    * @param obj the input bean
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} from the server
    */
  def addBean(obj: Any): CompletionStage[UpdateResponse] = super.addBean(obj = obj)

  /**
    * Adds a single bean specifying max time before it becomes committed
    * The bean is converted to a {@link SolrInputDocument} by the client's
    * {@link org.apache.solr.client.solrj.beans.DocumentObjectBinder}
    *
    * @param obj the input bean
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} from the server
    */
  def addBean(obj: Any, commitWithinMs: Int): CompletionStage[UpdateResponse] =
    super.addBean(obj = obj, commitWithinMs = commitWithinMs)

  /**
    * Adds a single bean specifying max time before it becomes committed
    * The bean is converted to a {@link SolrInputDocument} by the client's
    * {@link org.apache.solr.client.solrj.beans.DocumentObjectBinder}
    *
    * @param collection to Solr collection to add documents to
    * @param obj        the input bean
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} from the server
    */
  def addBean(collection: String, obj: Any): CompletionStage[UpdateResponse] = super.addBean(Option(collection), obj)

  /**
    * Adds a single bean specifying max time before it becomes committed
    * The bean is converted to a {@link SolrInputDocument} by the client's
    * {@link org.apache.solr.client.solrj.beans.DocumentObjectBinder}
    *
    * @param collection to Solr collection to add documents to
    * @param obj        the input bean
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} from the server
    */
  def addBean(collection: String, obj: Any, commitWithinMs: Int): CompletionStage[UpdateResponse] =
    super.addBean(Option(collection), obj, commitWithinMs)

  /**
    * Adds a collection of beans specifying max time before they become committed
    * The beans are converted to {@link SolrInputDocument}s by the client's
    * {@link org.apache.solr.client.solrj.beans.DocumentObjectBinder}
    *
    * @param beans the collection of beans
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} from the server
    */
  def addBeans(beans: util.Collection[_]): CompletionStage[UpdateResponse] = super.addBeans(beans = beans.asScala)

  /**
    * Adds a collection of beans specifying max time before they become committed
    * The beans are converted to {@link SolrInputDocument}s by the client's
    * {@link org.apache.solr.client.solrj.beans.DocumentObjectBinder}
    *
    * @param beans          the collection of beans
    * @param commitWithinMs max time (in ms) before a commit will happen
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} from the server
    */
  def addBeans(beans: util.Collection[_], commitWithinMs: Int): CompletionStage[UpdateResponse] =
    super.addBeans(beans = beans.asScala, commitWithinMs = commitWithinMs)

  /**
    * Adds a collection of beans specifying max time before they become committed
    * The beans are converted to {@link SolrInputDocument}s by the client's
    * {@link org.apache.solr.client.solrj.beans.DocumentObjectBinder}
    *
    * @param collection the Solr collection to add documents to
    * @param beans      the collection of beans
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} from the server
    */
  def addBeans(collection: String, beans: util.Collection[_]): CompletionStage[UpdateResponse] =
    super.addBeans(Option(collection), beans.asScala)

  /**
    * Adds a collection of beans specifying max time before they become committed
    * The beans are converted to {@link SolrInputDocument}s by the client's
    * {@link org.apache.solr.client.solrj.beans.DocumentObjectBinder}
    *
    * @param collection     the Solr collection to add documents to
    * @param beans          the collection of beans
    * @param commitWithinMs max time (in ms) before a commit will happen
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} from the server
    */
  def addBeans(collection: String, beans: util.Collection[_], commitWithinMs: Int): CompletionStage[UpdateResponse] =
    super.addBeans(Option(collection), beans.asScala, commitWithinMs)

  /**
    * Adds the beans supplied by the given iterator.
    *
    * @param beanIterator the iterator which returns Beans
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} from the server
    */
  def addBeans(beanIterator: util.Iterator[_]): CompletionStage[UpdateResponse] = super.addBeans(beanIterator.asScala)

  /**
    * Adds the beans supplied by the given iterator.
    *
    * @param collection   the Solr collection to add the documents to
    * @param beanIterator the iterator which returns Beans
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} from the server
    */
  def addBeans(collection: String, beanIterator: util.Iterator[_]): CompletionStage[UpdateResponse] =
    super.addBeans(collection, beanIterator.asScala)

  /**
    * Performs an explicit commit, causing pending documents to be committed for indexing
    *
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} containing the response
    *         from the server
    */
  def commit(): CompletionStage[UpdateResponse] = super.commit()

  /**
    * Performs an explicit commit, causing pending documents to be committed for indexing
    *
    * @param waitFlush    block until index changes are flushed to disk
    * @param waitSearcher block until a new searcher is opened and registered as the
    *                     main query searcher, making the changes visible
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} containing the response
    *         from the server
    */
  def commit(waitFlush: Boolean, waitSearcher: Boolean): CompletionStage[UpdateResponse] =
    super.commit(waitFlush = waitFlush, waitSearcher = waitSearcher)

  /**
    * Performs an explicit commit, causing pending documents to be committed for indexing
    *
    * @param waitFlush    block until index changes are flushed to disk
    * @param waitSearcher block until a new searcher is opened and registered as the
    *                     main query searcher, making the changes visible
    * @param softCommit   makes index changes visible while neither fsync-ing index files
    *                     nor writing a new index descriptor
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} containing the response
    *         from the server
    */
  def commit(waitFlush: Boolean, waitSearcher: Boolean, softCommit: Boolean): CompletionStage[UpdateResponse] =
    super.commit(waitFlush = waitFlush, waitSearcher = waitSearcher, softCommit = softCommit)

  /**
    * Performs an explicit commit, causing pending documents to be committed for indexing
    *
    * @param collection the Solr collection to send the commit to
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} containing the response
    *         from the server
    */
  def commit(collection: String): CompletionStage[UpdateResponse] = super.commit(Option(collection))

  /**
    * Performs an explicit commit, causing pending documents to be committed for indexing
    *
    * @param collection   the Solr collection to send the commit to
    * @param waitFlush    block until index changes are flushed to disk
    * @param waitSearcher block until a new searcher is opened and registered as the
    *                     main query searcher, making the changes visible
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} containing the response
    *         from the server
    */
  def commit(collection: String, waitFlush: Boolean, waitSearcher: Boolean): CompletionStage[UpdateResponse] =
    super.commit(Option(collection), waitFlush, waitSearcher)

  /**
    * Performs an explicit commit, causing pending documents to be committed for indexing
    *
    * @param collection   the Solr collection to send the commit to
    * @param waitFlush    block until index changes are flushed to disk
    * @param waitSearcher block until a new searcher is opened and registered as the
    *                     main query searcher, making the changes visible
    * @param softCommit   makes index changes visible while neither fsync-ing index files
    *                     nor writing a new index descriptor
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} containing the response
    *         from the server
    */
  def commit(collection: String, waitFlush: Boolean, waitSearcher: Boolean, softCommit: Boolean): CompletionStage[UpdateResponse] =
    super.commit(Option(collection), waitFlush, waitSearcher, softCommit)

  /**
    * Performs an explicit optimize, causing a merge of all segments to one.
    * Note: In most cases it is not required to do explicit optimize
    *
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} containing the response
    *         from the server
    */
  def optimize(): CompletionStage[UpdateResponse] = super.optimize()

  /**
    * Performs an explicit optimize, causing a merge of all segments to one.
    * Note: In most cases it is not required to do explicit optimize
    *
    * @param waitFlush    block until index changes are flushed to disk
    * @param waitSearcher block until a new searcher is opened and registered as
    *                     the main query searcher, making the changes visible
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} containing the response
    *         from the server
    */
  def optimize(waitFlush: Boolean, waitSearcher: Boolean): CompletionStage[UpdateResponse] =
    super.optimize(waitFlush = waitFlush, waitSearcher = waitSearcher)

  /**
    * Performs an explicit optimize, causing a merge of all segments to one.
    * Note: In most cases it is not required to do explicit optimize
    *
    * @param waitFlush    block until index changes are flushed to disk
    * @param waitSearcher block until a new searcher is opened and registered as
    *                     the main query searcher, making the changes visible
    * @param maxSegments  optimizes down to at most this number of segments
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} containing the response
    *         from the server
    */
  def optimize(waitFlush: Boolean, waitSearcher: Boolean, maxSegments: Int): CompletionStage[UpdateResponse] =
    super.optimize(waitFlush = waitFlush, waitSearcher = waitSearcher, maxSegments = maxSegments)

  /**
    * Performs an explicit optimize, causing a merge of all segments to one.
    * Note: In most cases it is not required to do explicit optimize
    *
    * @param collection the Solr collection to send the optimize to
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} containing the response
    *         from the server
    */
  def optimize(collection: String): CompletionStage[UpdateResponse] = super.optimize(Option(collection))

  /**
    * Performs an explicit optimize, causing a merge of all segments to one.
    * Note: In most cases it is not required to do explicit optimize
    *
    * @param collection   the Solr collection to send the optimize to
    * @param waitFlush    block until index changes are flushed to disk
    * @param waitSearcher block until a new searcher is opened and registered as
    *                     the main query searcher, making the changes visible
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} containing the response
    *         from the server
    */
  def optimize(collection: String, waitFlush: Boolean, waitSearcher: Boolean): CompletionStage[UpdateResponse] =
    super.optimize(Option(collection), waitFlush, waitSearcher)

  /**
    * Performs an explicit optimize, causing a merge of all segments to one.
    * Note: In most cases it is not required to do explicit optimize
    *
    * @param collection   the Solr collection to send the optimize to
    * @param waitFlush    block until index changes are flushed to disk
    * @param waitSearcher block until a new searcher is opened and registered as
    *                     the main query searcher, making the changes visible
    * @param maxSegments  optimizes down to at most this number of segments
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} containing the response
    *         from the server
    */
  def optimize(collection: String, waitFlush: Boolean, waitSearcher: Boolean, maxSegments: Int): CompletionStage[UpdateResponse] =
    super.optimize(Option(collection), waitFlush, waitSearcher, maxSegments)

  /**
    * Performs a rollback of all non-committed documents pending.
    * Note that this is not a true rollback as in databases. Content you have previously
    * added may have been committed due to autoCommit, buffer full, other client performing
    * a commit etc.
    *
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} containing the response
    *         from the server
    */
  def rollback(): CompletionStage[UpdateResponse] = super.rollback()

  /**
    * Performs a rollback of all non-committed documents pending.
    * Note that this is not a true rollback as in databases. Content you have previously
    * added may have been committed due to autoCommit, buffer full, other client performing
    * a commit etc.
    *
    * @param collection the Solr collection to send the rollback to
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} containing the response
    *         from the server
    */
  def rollback(collection: String): CompletionStage[UpdateResponse] = super.rollback(Option(collection))

  /**
    * Deletes a single document by unique ID, specifying max time before commit
    *
    * @param id the ID of the document to delete
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} containing the response
    *         from the server
    */
  def deleteById(id: String): CompletionStage[UpdateResponse] = super.deleteById(id = id)

  /**
    * Deletes a single document by unique ID, specifying max time before commit
    *
    * @param id             the ID of the document to delete
    * @param commitWithinMs max time (in ms) before a commit will happen
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} containing the response
    *         from the server
    */
  def deleteById(id: String, commitWithinMs: Int): CompletionStage[UpdateResponse] =
    super.deleteById(id = id, commitWithinMs = commitWithinMs)

  /**
    * Deletes a single document by unique ID, specifying max time before commit
    *
    * @param collection the Solr collection to delete the document from
    * @param id         the ID of the document to delete
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} containing the response
    *         from the server
    */
  def deleteById(collection: String, id: String): CompletionStage[UpdateResponse] =
    super.deleteById(Option(collection), id)

  /**
    * Deletes a single document by unique ID, specifying max time before commit
    *
    * @param collection the Solr collection to delete the document from
    * @param id         the ID of the document to delete
    * @param commitWithinMs max time (in ms) before a commit will happen
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} containing the response
    *         from the server
    */
  def deleteById(collection: String, id: String, commitWithinMs: Int): CompletionStage[UpdateResponse] =
    super.deleteById(Option(collection), id, commitWithinMs)

  /**
    * Deletes a list of documents by unique ID, specifying max time before commit
    *
    * @param ids the list of document IDs to delete
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} containing the response
    *         from the server
    */
  def deleteByIds(ids: util.List[String]): CompletionStage[UpdateResponse] = super.deleteByIds(ids = ids.asScala)

  /**
    * Deletes a list of documents by unique ID, specifying max time before commit
    *
    * @param ids            the list of document IDs to delete
    * @param commitWithinMs max time (in ms) before a commit will happen
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} containing the response
    *         from the server
    */
  def deleteByIds(ids: util.List[String], commitWithinMs: Int): CompletionStage[UpdateResponse] =
    super.deleteByIds(ids = ids.asScala, commitWithinMs = commitWithinMs)

  /**
    * Deletes a list of documents by unique ID, specifying max time before commit
    *
    * @param collection the Solr collection to delete the documents from
    * @param ids        the list of document IDs to delete
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} containing the response
    *         from the server
    */
  def deleteByIds(collection: String, ids: util.List[String]): CompletionStage[UpdateResponse] =
    super.deleteByIds(Option(collection), ids.asScala)

  /**
    * Deletes a list of documents by unique ID, specifying max time before commit
    *
    * @param collection     the Solr collection to delete the documents from
    * @param ids            the list of document IDs to delete
    * @param commitWithinMs max time (in ms) before a commit will happen
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} containing the response
    *         from the server
    */
  def deleteByIds(collection: String, ids: util.List[String], commitWithinMs: Int): CompletionStage[UpdateResponse] =
    super.deleteByIds(Option(collection), ids.asScala, commitWithinMs)

  /**
    * Deletes documents from the index based on a query, specifying max time before commit
    *
    * @param query the query expressing what documents to delete
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} containing the response
    *         from the server
    */
  def deleteByQuery(query: String): CompletionStage[UpdateResponse] = super.deleteByQuery(query = query)

  /**
    * Deletes documents from the index based on a query, specifying max time before commit
    *
    * @param query          the query expressing what documents to delete
    * @param commitWithinMs max time (in ms) before a commit will happen
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} containing the response
    *         from the server
    */
  def deleteByQuery(query: String, commitWithinMs: Int): CompletionStage[UpdateResponse] =
    super.deleteByQuery(query = query, commitWithinMs = commitWithinMs)

  /**
    * Deletes documents from the index based on a query, specifying max time before commit
    *
    * @param collection the Solr collection to delete the documents from
    * @param query      the query expressing what documents to delete
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} containing the response
    *         from the server
    */
  def deleteByQuery(collection: String, query: String): CompletionStage[UpdateResponse] =
    super.deleteByQuery(Option(collection), query)

  /**
    * Deletes documents from the index based on a query, specifying max time before commit
    *
    * @param collection     the Solr collection to delete the documents from
    * @param query          the query expressing what documents to delete
    * @param commitWithinMs max time (in ms) before a commit will happen
    * @return an { @link org.apache.solr.client.solrj.response.UpdateResponse} containing the response
    *         from the server
    */
  def deleteByQuery(collection: String, query: String, commitWithinMs: Int): CompletionStage[UpdateResponse] =
    super.deleteByQuery(Option(collection), query, commitWithinMs)

  /**
    * Issues a ping request to check if the server is alive
    *
    * @return a { @link org.apache.solr.client.solrj.response.SolrPingResponse} containing the response
    *         from the server
    */
  override def ping(): CompletionStage[SolrPingResponse] = super.ping()

  /**
    * Performs a query to the Solr server
    *
    * @param q an object holding all key/value parameters to send along the request
    * @return a { @link org.apache.solr.client.solrj.response.QueryResponse} containing the response
    *         from the server
    */
  override def query(q: SolrParams): CompletionStage[QueryResponse] = super.query(q)

  /**
    * Performs a query to the Solr server
    *
    * @param q      an object holding all key/value parameters to send along the request
    * @param method specifies the HTTP method to use for the request, such as GET or POST
    * @return a { @link org.apache.solr.client.solrj.response.QueryResponse} containing the response
    *         from the server
    */
  override def query(q: SolrParams, method: METHOD): CompletionStage[QueryResponse] = super.query(q, method)

  /**
    * Performs a query to the Solr server
    *
    * @param collection the Solr collection to query
    * @param q          an object holding all key/value parameters to send along the request
    * @return a { @link org.apache.solr.client.solrj.response.QueryResponse} containing the response
    *         from the server
    */
  def query(collection: String, q: SolrParams): CompletionStage[QueryResponse] = super.query(collection, q)

  /**
    * Performs a query to the Solr server
    *
    * @param collection the Solr collection to query
    * @param q          an object holding all key/value parameters to send along the request
    * @param method     specifies the HTTP method to use for the request, such as GET or POST
    * @return a { @link org.apache.solr.client.solrj.response.QueryResponse} containing the response
    *         from the server
    */
  override def query(collection: String, q: SolrParams, method: METHOD): CompletionStage[QueryResponse] =
    super.query(collection, q, method)

  /**
    * Query solr, and stream the results.  Unlike the standard query, this will
    * send events for each Document rather then add them to the QueryResponse.
    *
    * Although this function returns a 'QueryResponse' it should be used with care
    * since it excludes anything that was passed to callback.  Also note that
    * future version may pass even more info to the callback and may not return
    * the results in the QueryResponse.
    *
    * @param q        an object holding all key/value parameters to send along the request
    * @param callback the callback to stream results to
    * @return a { @link org.apache.solr.client.solrj.response.QueryResponse} containing the response
    *         from the server
    */
  def queryAndStreamResponse(q: SolrParams, callback: StreamingResponseCallback): CompletionStage[QueryResponse] =
    super.queryAndStreamResponse(q = q, callback = callback)

  /**
    * Query solr, and stream the results.  Unlike the standard query, this will
    * send events for each Document rather then add them to the QueryResponse.
    *
    * Although this function returns a 'QueryResponse' it should be used with care
    * since it excludes anything that was passed to callback.  Also note that
    * future version may pass even more info to the callback and may not return
    * the results in the QueryResponse.
    *
    * @param collection the Solr collection to query
    * @param q          an object holding all key/value parameters to send along the request
    * @param callback   the callback to stream results to
    * @return a { @link org.apache.solr.client.solrj.response.QueryResponse} containing the response
    *         from the server
    */
  def queryAndStreamResponse(collection: String, q: SolrParams, callback: StreamingResponseCallback): CompletionStage[QueryResponse] =
    super.queryAndStreamResponse(Option(collection), q, callback)

  /**
    * Retrieves the SolrDocument associated with the given identifier and uses
    * the SolrParams to execute the request.
    *
    * @param id     the id
    * @return retrieved SolrDocument, or None if no document is found.
    */
  def getById(id: String): CompletionStage[Optional[SolrDocument]] =
    super.getById(id = id).thenApply(asJavaFunction(toJava))

  /**
    * Retrieves the SolrDocument associated with the given identifier and uses
    * the SolrParams to execute the request.
    *
    * @param id     the id
    * @param params additional parameters to add to the query
    * @return retrieved SolrDocument, or None if no document is found.
    */
  def getById(id: String, params: SolrParams): CompletionStage[Optional[SolrDocument]] =
    super.getById(id = id, params = Option(params)).thenApply(asJavaFunction(toJava))

  /**
    * Retrieves the SolrDocument associated with the given identifier and uses
    * the SolrParams to execute the request.
    *
    * @param collection the Solr collection to query
    * @param id         the id
    * @return retrieved SolrDocument, or None if no document is found.
    */
  def getById(collection: String, id: String): CompletionStage[Optional[SolrDocument]] =
    super.getById(Option(collection), id).thenApply(asJavaFunction(toJava))

  /**
    * Retrieves the SolrDocument associated with the given identifier and uses
    * the SolrParams to execute the request.
    *
    * @param collection the Solr collection to query
    * @param id         the id
    * @param params     additional parameters to add to the query
    * @return retrieved SolrDocument, or None if no document is found.
    */
  def getById(collection: String, id: String, params: SolrParams): CompletionStage[Optional[SolrDocument]] =
    super.getById(Option(collection), id, Option(params)).thenApply(asJavaFunction(toJava))

  /**
    * Retrieves the SolrDocuments associated with the given identifiers and uses
    * the SolrParams to execute the request.
    *
    * If a document was not found, it will not be added to the SolrDocumentList.
    *
    * @param ids the ids
    * @return a SolrDocumentList, or null if no documents were found
    */
  def getByIds(ids: util.Collection[String]): CompletionStage[SolrDocumentList] = super.getByIds(ids = ids.asScala)

  /**
    * Retrieves the SolrDocuments associated with the given identifiers and uses
    * the SolrParams to execute the request.
    *
    * If a document was not found, it will not be added to the SolrDocumentList.
    *
    * @param ids    the ids
    * @param params additional parameters to add to the query
    * @return a SolrDocumentList, or null if no documents were found
    */
  def getByIds(ids: util.Collection[String], params: SolrParams): CompletionStage[SolrDocumentList] =
    super.getByIds(ids = ids.asScala, params = Option(params))

  /**
    * Retrieves the SolrDocuments associated with the given identifiers and uses
    * the SolrParams to execute the request.
    *
    * If a document was not found, it will not be added to the SolrDocumentList.
    *
    * @param collection the Solr collection to query
    * @param ids        the ids
    * @return a SolrDocumentList, or null if no documents were found
    */
  def getByIds(collection: String, ids: util.Collection[String]): CompletionStage[SolrDocumentList] =
    super.getByIds(Option(collection), ids.asScala)

  /**
    * Retrieves the SolrDocuments associated with the given identifiers and uses
    * the SolrParams to execute the request.
    *
    * If a document was not found, it will not be added to the SolrDocumentList.
    *
    * @param collection the Solr collection to query
    * @param ids        the ids
    * @param params     additional parameters to add to the query
    * @return a SolrDocumentList, or null if no documents were found
    */
  def getByIds(collection: String, ids: util.Collection[String], params: SolrParams): CompletionStage[SolrDocumentList] =
    super.getByIds(Option(collection), ids.asScala, Option(params))

  /**
    * Performs a query to a solr server taking the preferred server into account if provided.
    *
    * @param q         the query to send to the solr server.
    * @param preferred the server that should be preferred to process the query. Specific [[io.ino.solrs.LoadBalancer LoadBalancer]]
    *                  implementations have to support this and might add their own semantics.
    * @return the response and the server that handled the query.
    */
  override def queryPreferred(q: SolrQuery, preferred: Option[SolrServer]): CompletionStage[(QueryResponse, SolrServer)] =
    super.queryPreferred(q, preferred)
}

object JavaAsyncSolrClient extends TypedAsyncSolrClient[CompletionStage, JavaAsyncSolrClient] {

  private implicit val ff = JavaFutureFactory

  override protected def futureFactory: FutureFactory[CompletionStage] = JavaFutureFactory

  def create(url: String): JavaAsyncSolrClient = builder(url).build

  override def builder(url: String): Builder[CompletionStage, JavaAsyncSolrClient] = new Builder(url, build _)
  override def builder(loadBalancer: LoadBalancer): Builder[CompletionStage, JavaAsyncSolrClient] = new Builder(loadBalancer, build _)

  override protected def build(loadBalancer: LoadBalancer,
                               httpClient: AsyncHttpClient,
                               shutdownHttpClient: Boolean,
                               requestInterceptor: Option[RequestInterceptor],
                               requestWriter: RequestWriter,
                               responseParser: ResponseParser,
                               metrics: Metrics,
                               serverStateObservation: Option[ServerStateObservation[CompletionStage]],
                               retryPolicy: RetryPolicy): JavaAsyncSolrClient =
    new JavaAsyncSolrClient(
      loadBalancer,
      httpClient,
      shutdownHttpClient,
      requestInterceptor,
      requestWriter,
      responseParser,
      metrics,
      serverStateObservation,
      retryPolicy)
}