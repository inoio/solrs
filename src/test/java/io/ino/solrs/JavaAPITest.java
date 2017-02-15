package io.ino.solrs;

import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.impl.XMLResponseParser;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.asynchttpclient.DefaultAsyncHttpClient;
import org.junit.BeforeClass;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;
import scala.Option;
import scala.Tuple2;
import scala.concurrent.duration.FiniteDuration;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class JavaAPITest extends JUnitSuite {

    private static final long serialVersionUID = 1;

    private static SolrRunner solrRunner;

    @BeforeClass
    public static void beforeClass() {
        solrRunner = SolrRunner.startOnce(8888, Option.empty()).awaitReady(10, SECONDS);
    }

    @Test
    public void testAsyncSolrClientBuilderUrl() throws ExecutionException, InterruptedException {
        JavaAsyncSolrClient solr = JavaAsyncSolrClient.builder("http://localhost:" + solrRunner.port() + "/solr/collection1")
                .withHttpClient(new DefaultAsyncHttpClient())
                .withResponseParser(new XMLResponseParser())
                .build();
        CompletionStage<QueryResponse> response = solr.query(new SolrQuery("*:*"));
        response.thenAccept(r -> System.out.println("found "+ r.getResults().getNumFound() +" docs"));
        assertNotNull(response.toCompletableFuture().get().getResults());
    }

    @Test
    public void testRoundRobinLB() {
        RoundRobinLB lb = RoundRobinLB.create(Arrays.asList("http://localhost:8983/solr/collection1", "http://localhost:8984/solr/collection1"));
        assertNotNull(lb);
    }

    @Test
    public void testFastestServerLB() {
        StaticSolrServers servers = StaticSolrServers.create(Arrays.asList("http://localhost:8983/solr/collection1", "http://localhost:8984/solr/collection1"));
        // TODO: api feels unnatural
        Tuple2<String, SolrQuery> col1TestQuery = new Tuple2<>("collection1", new SolrQuery("*:*").setRows(0));
        Function<SolrServer, Tuple2<String, SolrQuery>> collectionAndTestQuery = server -> col1TestQuery;
        FastestServerLB<?> lb = null;
        try {
            lb = FastestServerLB.builder(servers, collectionAndTestQuery)
                    .withMinDelay(50, TimeUnit.MILLISECONDS)
                    .withMaxDelay(5, TimeUnit.SECONDS)
                    .withFilterFastServers(averageDuration -> (server, duration) -> duration <= averageDuration)
                    .withMapPredictedResponseTime(prediction -> prediction * 10)
                    .withInitialTestRuns(50)
                    .build();

            assertNotNull(lb);

            JavaAsyncSolrClient solrs = JavaAsyncSolrClient.builder(lb).build();
            assertNotNull(solrs.loadBalancer());
        } finally {
            if(lb != null) lb.shutdown();
        }
    }

    @Test
    public void testAsyncSolrClientBuilderLB() throws ExecutionException, InterruptedException {
        JavaAsyncSolrClient solr = JavaAsyncSolrClient.builder(new SingleServerLB("http://localhost:" + solrRunner.port() + "/solr/collection1"))
                .withHttpClient(new DefaultAsyncHttpClient())
                .withResponseParser(new XMLResponseParser())
                .build();
        CompletionStage<QueryResponse> response = solr.query(new SolrQuery("*:*"));
        response.thenAccept(r -> System.out.println("found "+ r.getResults().getNumFound() +" docs"));
        assertNotNull(response.toCompletableFuture().get().getResults());
    }

    @Test
    public void testStaticSolrServers() {
        StaticSolrServers servers = StaticSolrServers.create(Arrays.asList("http://localhost:8983/solr/collection1", "http://localhost:8984/solr/collection1"));
        assertNotNull(servers);
    }

    @Test
    public void testCloudSolrServers() {
        CloudSolrServers.Builder builder = CloudSolrServers.builder("host1:2181,host2:2181")
                .withZkClientTimeout(15, SECONDS)
                .withZkConnectTimeout(10, SECONDS)
                .withClusterStateUpdateInterval(1, SECONDS)
                .withDefaultCollection("collection1")
                .withWarmupQueries((collection) -> Collections.singletonList(new SolrQuery("*:*")), 10);
        assertNotNull(builder);
        assertEquals("host1:2181,host2:2181", builder.zkHost());
        assertEquals(FiniteDuration.apply(15, SECONDS), builder.zkClientTimeout());
        assertEquals(FiniteDuration.apply(10, SECONDS), builder.zkConnectTimeout());
        assertEquals(FiniteDuration.apply(1, SECONDS), builder.clusterStateUpdateInterval());
        assertEquals(Option.apply("collection1"), builder.defaultCollection());
        assertTrue(builder.warmupQueries().isDefined());
        assertEquals(10, builder.warmupQueries().get().count());

        CloudSolrServers<?> build = builder.build();
    }

    @Test
    public void testRetryPolicy() {
        assertNotNull(RetryPolicy.AtMost(2));
        assertNotNull(RetryPolicy.TryAvailableServers());
        assertNotNull(RetryPolicy.TryOnce());
    }

}
