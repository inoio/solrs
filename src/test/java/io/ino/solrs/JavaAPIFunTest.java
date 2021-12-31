package io.ino.solrs;

import static java.util.Arrays.asList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.toList;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.collection.IsIterableContainingInAnyOrder.containsInAnyOrder;
import static org.junit.Assert.*;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.SolrServerException;
import org.apache.solr.client.solrj.beans.Field;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrDocumentList;
import org.apache.solr.common.SolrInputDocument;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import org.scalatestplus.junit.JUnitSuite;
import scala.Option;

public class JavaAPIFunTest extends JUnitSuite {

    private static final long serialVersionUID = 1;

    private static SolrRunner solrRunner;

    private static SolrClient solr;

    private static JavaAsyncSolrClient solrs;

    @BeforeClass
    public static void beforeClass() {
        solrRunner = SolrRunner.startOnce(8888).awaitReady(10, SECONDS);
        String url = "http://localhost:" + solrRunner.port() + "/solr/collection1";
        solr = new HttpSolrClient.Builder(url).build();
        solrs = JavaAsyncSolrClient.create(url);
    }

    @Before
    public void before() throws IOException, SolrServerException {
        solr.deleteByQuery("*:*");
    }

    @AfterClass
    public static void afterClass() throws IOException {
        solr.close();
        solrs.shutdown();
    }

    @Test
    public void testAddDocsAsCollection() throws ExecutionException, InterruptedException, IOException, SolrServerException {
        SolrInputDocument doc1 = newInputDoc("id1", "doc1", "cat1", 10);
        SolrInputDocument doc2 = newInputDoc("id2", "doc2", "cat1", 20);
        solrs.addDocs(asList(doc1, doc2)).toCompletableFuture().get();
        solr.commit();
        SolrDocumentList docs = solr.query(new SolrQuery("*:*")).getResults();
        assertThat(docs.getNumFound(), equalTo(2L));
        assertThat(docs.stream().map(this::getPrice).collect(toList()), containsInAnyOrder(10f, 20f));
    }

    @Test
    public void testAddDocsAsIterator() throws ExecutionException, InterruptedException, IOException, SolrServerException {
        SolrInputDocument doc1 = newInputDoc("id1", "doc1", "cat1", 10);
        SolrInputDocument doc2 = newInputDoc("id2", "doc2", "cat1", 20);
        solrs.addDocs(asList(doc1, doc2).iterator()).toCompletableFuture().get();
        solr.commit();
        SolrDocumentList docs = solr.query(new SolrQuery("*:*")).getResults();
        assertThat(docs.getNumFound(), equalTo(2L));
        assertThat(docs.stream().map(this::getPrice).collect(toList()), containsInAnyOrder(10f, 20f));
    }

    @Test
    public void testAddDoc() throws ExecutionException, InterruptedException, IOException, SolrServerException {
        solrs.addDoc(newInputDoc("id1", "doc1", "cat1", 10)).toCompletableFuture().get();
        solr.commit();
        SolrDocumentList docs = solr.query(new SolrQuery("*:*")).getResults();
        assertThat(docs.getNumFound(), equalTo(1L));
        assertThat(docs.stream().map(this::getPrice).collect(toList()), containsInAnyOrder(10f));
    }

    @Test
    public void testAddBean() throws ExecutionException, InterruptedException, IOException, SolrServerException {
        TestBean bean = new TestBean("id1", "doc1", "cat1", 10);
        solrs.addBean(bean).toCompletableFuture().get();
        solr.commit();
        QueryResponse response = solr.query(new SolrQuery("*:*"));
        assertThat(response.getResults().getNumFound(), equalTo(1L));
        assertThat(response.getBeans(TestBean.class), containsInAnyOrder(bean));
    }

    @Test
    public void testAddBeansAsIterable() throws ExecutionException, InterruptedException, IOException, SolrServerException {
        TestBean bean1 = new TestBean("id1", "doc1", "cat1", 10);
        TestBean bean2 = new TestBean("id2", "doc2", "cat1", 20);
        solrs.addBeans(asList(bean1, bean2)).toCompletableFuture().get();
        solr.commit();
        QueryResponse response = solr.query(new SolrQuery("*:*"));
        assertThat(response.getResults().getNumFound(), equalTo(2L));
        assertThat(response.getBeans(TestBean.class), containsInAnyOrder(bean1, bean2));
    }

    @Test
    public void testAddBeansAsIterator() throws ExecutionException, InterruptedException, IOException, SolrServerException {
        TestBean bean1 = new TestBean("id1", "doc1", "cat1", 10);
        TestBean bean2 = new TestBean("id2", "doc2", "cat1", 20);
        solrs.addBeans(asList(bean1, bean2).iterator()).toCompletableFuture().get();
        solr.commit();
        QueryResponse response = solr.query(new SolrQuery("*:*"));
        assertThat(response.getResults().getNumFound(), equalTo(2L));
        assertThat(response.getBeans(TestBean.class), containsInAnyOrder(bean1, bean2));
    }

    @Test
    public void testCommit() throws ExecutionException, InterruptedException, IOException, SolrServerException {
        solr.add(newInputDoc("id1", "doc1", "cat1", 10));
        solrs.commit();
        SolrDocumentList docs = solr.query(new SolrQuery("*:*")).getResults();
        assertThat(docs.getNumFound(), equalTo(1L));
        assertThat(docs.stream().map(this::getPrice).collect(toList()), containsInAnyOrder(10f));
    }

    @Test
    public void testDeleteById() throws ExecutionException, InterruptedException, IOException, SolrServerException {
        SolrInputDocument doc1 = newInputDoc("id1", "doc1", "cat1", 10);
        SolrInputDocument doc2 = newInputDoc("id2", "doc2", "cat1", 20);
        solr.add(asList(doc1, doc2));
        solr.commit();
        solrs.deleteById("id1").toCompletableFuture().get();
        solr.commit();
        SolrDocumentList docs = solr.query(new SolrQuery("*:*")).getResults();
        assertThat(docs.getNumFound(), equalTo(1L));
        assertThat(docs.stream().map(this::getPrice).collect(toList()), containsInAnyOrder(20f));
    }

    @Test
    public void testDeleteByIds() throws ExecutionException, InterruptedException, IOException, SolrServerException {
        SolrInputDocument doc1 = newInputDoc("id1", "doc1", "cat1", 10);
        SolrInputDocument doc2 = newInputDoc("id2", "doc2", "cat1", 20);
        SolrInputDocument doc3 = newInputDoc("id3", "doc3", "cat2", 30);
        solr.add(asList(doc1, doc2, doc3));
        solr.commit();
        solrs.deleteByIds(asList("id1", "id2")).toCompletableFuture().get();
        solr.commit();
        SolrDocumentList docs = solr.query(new SolrQuery("*:*")).getResults();
        assertThat(docs.getNumFound(), equalTo(1L));
        assertThat(docs.stream().map(this::getPrice).collect(toList()), containsInAnyOrder(30f));
    }

    @Test
    public void testDeleteByQuery() throws ExecutionException, InterruptedException, IOException, SolrServerException {
        SolrInputDocument doc1 = newInputDoc("id1", "doc1", "cat1", 10);
        SolrInputDocument doc2 = newInputDoc("id2", "doc2", "cat1", 20);
        SolrInputDocument doc3 = newInputDoc("id3", "doc3", "cat2", 30);
        solr.add(asList(doc1, doc2, doc3));
        solr.commit();
        solrs.deleteByQuery("cat:cat1").toCompletableFuture().get();
        solr.commit();
        SolrDocumentList docs = solr.query(new SolrQuery("*:*")).getResults();
        assertThat(docs.getNumFound(), equalTo(1L));
        assertThat(docs.stream().map(this::getPrice).collect(toList()), containsInAnyOrder(30f));
    }

    @Test
    public void testQuery() throws ExecutionException, InterruptedException, IOException, SolrServerException {
        SolrInputDocument doc1 = newInputDoc("id1", "doc1", "cat1", 10);
        SolrInputDocument doc2 = newInputDoc("id2", "doc2", "cat1", 20);
        SolrInputDocument doc3 = newInputDoc("id3", "doc3", "cat2", 30);
        solr.add(asList(doc1, doc2, doc3));
        solr.commit();
        SolrDocumentList docs = solrs.query(new SolrQuery("cat:cat1")).toCompletableFuture().get().getResults();
        assertThat(docs.getNumFound(), equalTo(2L));
        assertThat(docs.stream().map(this::getPrice).collect(toList()), containsInAnyOrder(10f, 20f));
    }

    @Test
    public void testGetById() throws ExecutionException, InterruptedException, IOException, SolrServerException {
        SolrInputDocument doc1 = newInputDoc("id1", "doc1", "cat1", 10);
        SolrInputDocument doc2 = newInputDoc("id2", "doc2", "cat1", 20);
        solr.add(asList(doc1, doc2));
        solr.commit();
        assertThat(solrs.getById("id1").toCompletableFuture().get().map(this::getPrice), equalTo(Optional.of(10f)));
    }

    @Test
    public void testGetByIdAbsent() throws ExecutionException, InterruptedException, IOException, SolrServerException {
        solr.add(newInputDoc("id1", "doc1", "cat1", 10));
        solr.commit();
        assertFalse(solrs.getById("id2").toCompletableFuture().get().isPresent());
    }

    @Test
    public void testGetByIds() throws ExecutionException, InterruptedException, IOException, SolrServerException {
        SolrInputDocument doc1 = newInputDoc("id1", "doc1", "cat1", 10);
        SolrInputDocument doc2 = newInputDoc("id2", "doc2", "cat1", 20);
        SolrInputDocument doc3 = newInputDoc("id3", "doc3", "cat2", 30);
        solr.add(asList(doc1, doc2, doc3));
        solr.commit();
        SolrDocumentList docs = solrs.getByIds(asList("id1", "id2")).toCompletableFuture().get();
        assertThat(docs.getNumFound(), equalTo(2L));
        assertThat(docs.stream().map(this::getPrice).collect(toList()), containsInAnyOrder(10f, 20f));
    }

    @Test
    public void testGetByIdsAbsent() throws ExecutionException, InterruptedException, IOException, SolrServerException {
        solr.add(newInputDoc("id1", "doc1", "cat1", 10));
        solr.commit();
        SolrDocumentList docs = solrs.getByIds(asList("id2", "id3")).toCompletableFuture().get();
        assertThat(docs.getNumFound(), equalTo(0L));
        assertTrue(docs.isEmpty());
    }

    private SolrInputDocument newInputDoc(String id, String name, String category, float price) {
        SolrInputDocument doc = new SolrInputDocument();
        doc.addField("id", id);
        doc.addField("name", name);
        doc.addField("cat", category);
        doc.addField("price", price);
        return doc;
    }

    private Object getPrice(SolrDocument doc) {
        return doc.getFieldValue("price");
    }

    public static class TestBean {
        @Field
        private String id;

        @Field
        private String name;

        @Field
        private String category;

        @Field
        private float price;

        public TestBean() {
        }

        TestBean(String id, String name, String category, float price) {
            this.id = id;
            this.name = name;
            this.category = category;
            this.price = price;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getCategory() {
            return category;
        }

        public void setCategory(String category) {
            this.category = category;
        }

        public float getPrice() {
            return price;
        }

        public void setPrice(float price) {
            this.price = price;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TestBean testBean = (TestBean) o;
            return Float.compare(testBean.price, price) == 0 &&
                    Objects.equals(id, testBean.id) &&
                    Objects.equals(name, testBean.name) &&
                    Objects.equals(category, testBean.category);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, name, category, price);
        }
    }
}
