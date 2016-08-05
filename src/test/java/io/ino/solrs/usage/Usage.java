package io.ino.solrs.usage;

import io.ino.solrs.AsyncSolrClient;
import io.ino.solrs.CloudSolrServers;
import io.ino.solrs.FastestServerLB;
import io.ino.solrs.JavaAsyncSolrClient;
import io.ino.solrs.RequestInterceptor;
import io.ino.solrs.RetryPolicy;
import io.ino.solrs.RoundRobinLB;
import io.ino.solrs.SolrServer;
import io.ino.solrs.StaticSolrServers;
import io.ino.solrs.future.Future;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.impl.XMLResponseParser;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.asynchttpclient.DefaultAsyncHttpClient;
import scala.Function1;
import scala.Function2;
import scala.Tuple2;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static java.util.concurrent.TimeUnit.*;

public class Usage {

	{
		JavaAsyncSolrClient solr = JavaAsyncSolrClient.create("http://localhost:8983/solr/collection1");
		CompletableFuture<QueryResponse> response = solr.query(new SolrQuery("java"));
		response.thenAccept(r -> System.out.println("found " + r.getResults().getNumFound() + " docs"));
	}

	{
		JavaAsyncSolrClient solr = JavaAsyncSolrClient.builder("http://localhost:8983/solr/collection1")
				.withHttpClient(new DefaultAsyncHttpClient())
				.withResponseParser(new XMLResponseParser())
				.build();
	}

	{
		RoundRobinLB lb = RoundRobinLB.create(Arrays.asList("http://localhost:8983/solr/collection1", "http://localhost:8984/solr/collection1"));
		JavaAsyncSolrClient solr = JavaAsyncSolrClient.builder(lb).build();
	}

	{
		StaticSolrServers servers = StaticSolrServers.create(Arrays.asList("http://localhost:8983/solr/collection1", "http://localhost:8984/solr/collection1"));
		// TODO: api feels unnatural
		Tuple2<String, SolrQuery> col1TestQuery = new Tuple2<>("collection1", new SolrQuery("*:*").setRows(0));
		Function<SolrServer, Tuple2<String, SolrQuery>> collectionAndTestQuery = server -> col1TestQuery;
		FastestServerLB<?> lb = FastestServerLB.builder(servers, collectionAndTestQuery)
				.withMinDelay(50, MILLISECONDS)
				.withMaxDelay(5, SECONDS)
				.withInitialTestRuns(50)
				.build();
		JavaAsyncSolrClient solr = JavaAsyncSolrClient.builder(lb).build();

		// finally also shutdown the lb...
		lb.shutdown();
	}

	{
		CloudSolrServers<?> servers = CloudSolrServers.builder("localhost:2181").build();
		JavaAsyncSolrClient solr = JavaAsyncSolrClient.builder(new RoundRobinLB(servers)).build();
	}

	{
		CloudSolrServers<?> servers = CloudSolrServers.builder("host1:2181,host2:2181")
				.withZkClientTimeout(15, SECONDS)
				.withZkConnectTimeout(10, SECONDS)
				.withClusterStateUpdateInterval(1, SECONDS)
				.withDefaultCollection("collection1")
				.withWarmupQueries((collection) -> Collections.singletonList(new SolrQuery("*:*")), 10)
				.build();
		JavaAsyncSolrClient solr = JavaAsyncSolrClient.builder(new RoundRobinLB(servers)).build();
	}

	{
		JavaAsyncSolrClient solr = JavaAsyncSolrClient
				.builder(new RoundRobinLB(CloudSolrServers.builder("localhost:2181").build()))
				.withRetryPolicy(RetryPolicy.TryAvailableServers())
				.build();


	}

	{
	}

}