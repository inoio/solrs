package io.ino.solrs

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

import org.apache.commons.io.FileUtils
import org.apache.solr.client.solrj.embedded.JettyConfig
import org.apache.solr.client.solrj.embedded.JettySolrRunner
import org.apache.solr.client.solrj.impl.CloudSolrClient
import org.apache.solr.client.solrj.request.CollectionAdminRequest
import org.apache.solr.cloud.MiniSolrCloudCluster
import org.apache.solr.cloud.ZkTestServer
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._

// Collection information for SolrCloud
case class SolrCollection(name: String, replicas: Int = 1, shards: Int = 1)

/**
  * A runner for a s Solr Cloud cluster using [[MiniSolrCloudCluster]]. Will start up embedded Zookeeper.
  *
  * @param numServers        the number of Solr Cloud instances in this cluster
  * @param collections       (optional) a list of collections (name, # replica / shard) to be used in this cluster,
  *                          their config must exist below SolrHome (see below). Will be uploaded to ZK and core(s) will be created.
  * @param defaultCollection (optional) the default collection the [[solrJClient]] should query
  * @param maybeZkPort       (optional) the port to start Zookeeper on, a random port is chosen if empty
  * @param maybeSolrHome     (optional) a Solr home dir to use, tries to locate resource /solr-home in classpath if not specified
  *
  */
class SolrCloudRunner(numServers: Int, collections: List[SolrCollection] = List.empty,
                      defaultCollection: Option[String] = None,
                      maybeZkPort: Option[Int],
                      maybeSolrHome: Option[Path] = None) {


  import SolrCloudRunner._

  @volatile
  private var zookeeper: ZkTestServer = _

  @volatile
  private var miniSolrCloudCluster: MiniSolrCloudCluster = _

  // init "base" = some temp dir for this run
  val baseDir: Path = createBaseDir()

  // create a copy of the given (or default) Solr home below "base" directory
  val solrHome: Path = makeSolrHomeDirIn(baseDir)

  // avoid confusing ERROR log entry "Missing Java Option solr.log.dir"
  System.setProperty("solr.log.dir", baseDir.toAbsolutePath.toString)

  // shutdown hook clean up for tests that don't call shutdown explicitly
  Runtime.getRuntime.addShutdownHook(new Thread() {
    override def run(): Unit = {
      shutdown()
    }
  })

  def start(): SolrCloudRunner = {
    import SolrCloudRunner._

    // scalastyle:off null
    if (miniSolrCloudCluster != null) {
      throw new IllegalStateException("Cluster already running.")
    }
    if (zookeeper != null) {
      throw new IllegalStateException("Solr ZK Test Server already running.")
    }
    // scalastyle:on null

    timed("Starting Solr ZK Test Server") {
      val dataDir = baseDir.resolve("zookeeper/server1/data")
      zookeeper = maybeZkPort.map(zkPort => new ZkTestServer(dataDir, zkPort)).getOrElse(new ZkTestServer(dataDir))
      startZk(zookeeper)
    }

    timed(s"Starting Mini Solr Cloud cluster with $numServers node(s)") {
      miniSolrCloudCluster = new MiniSolrCloudCluster(numServers, solrHome, MiniSolrCloudCluster.DEFAULT_CLOUD_SOLR_XML,
        new JettyConfig.Builder().build(), zookeeper)
    }

    for (coll <- collections) {
      val collectionName = coll.name
      // just use collection name = config name
      val configName = collectionName
      val confDir = solrHome.resolve(collectionName).resolve("conf")
      timed(s"Uploading config '$configName' for collection '$collectionName' from $confDir") {
        miniSolrCloudCluster.uploadConfigSet(confDir, configName)
      }
      val result = timed(s"Creating collection '$collectionName' with replicas=${coll.replicas} and shards=${coll.shards}") {
        CollectionAdminRequest.createCollection(collectionName, configName, coll.shards, coll.replicas)
          .process(miniSolrCloudCluster.getSolrClient)
      }
      logger.info(s"Success: ${result.isSuccess}, Status: ${result.getCollectionStatus}")
    }

    for ((url, idx) <- solrCoreUrls.zipWithIndex) {
      logger.info(s"Jetty core #$idx running at $url")
    }

    // mutate the MiniSolrCloudCluster's SolrClient instance and set its default collection
    for (coll <- defaultCollection) {
      miniSolrCloudCluster.getSolrClient.setDefaultCollection(coll)
    }

    this
  }

  def shutdown(): Unit = {
    // scalastyle:off null
    if (miniSolrCloudCluster != null) {
      logger.info("Shutting down Solr Cloud cluster")
      miniSolrCloudCluster.shutdown()
      miniSolrCloudCluster = null
    }
    if (zookeeper != null) {
      logger.info("Shutting down Zookeeper")
      zookeeper.shutdown()
      zookeeper = null
    }
    // scalastyle:on null
    scala.util.control.Exception.ignoring(classOf[IOException]) {
      FileUtils.deleteDirectory(baseDir.toFile)
    }
  }

  def solrJClient: CloudSolrClient = miniSolrCloudCluster.getSolrClient

  def jettySolrRunners: List[JettySolrRunner] = {
    import scala.collection.JavaConverters._
    miniSolrCloudCluster.getJettySolrRunners.asScala.toList
  }

  def zkAddress: String = zookeeper.getZkAddress

  def restartZookeeper(): Unit = {
    logger.info(s"Restarting Zookeeper with zkDir = ${zookeeper.getZkDir} and port = ${zookeeper.getPort}...")
    zookeeper.shutdown()
    logger.info(s"Zookeeper was stopped, starting new one...")
    zookeeper = new ZkTestServer(zookeeper.getZkDir, zookeeper.getPort)
    startZk(zookeeper)
    logger.info(s"New Zookeeper was started")
  }

  private def startZk(zk: ZkTestServer): Unit = {
    // ignore frequent WATCH over limit warnings on shutdown (probably caused by Solr not removing ZK watches)
    zk.setViolationReportAction(ZkTestServer.LimitViolationAction.IGNORE)
    zk.getLimiter.setAction(ZkTestServer.LimitViolationAction.IGNORE)
    zk.run()
  }

  def solrCoreUrls: List[String] = {
    jettySolrRunners.flatMap { jetty =>
      jetty.getCoreContainer.getAllCoreNames.asScala.map { coreName =>
        s"http://127.0.0.1:${jetty.getLocalPort}/solr/$coreName"
      }
    }
  }

  private def makeSolrHomeDirIn(baseDir: Path): Path = {
    val solrHomeSourceDir = maybeSolrHome.map(_.toFile).getOrElse {
      // read from src/test/resources, because sbt does not copy the symlink solr-home/collection2/conf when copying to target...
      Paths.get("./src/test/resources/solr-home").toAbsolutePath.normalize().toFile
    }
    val solrHome = Files.createDirectories(baseDir.resolve("solrhome"))
    BetterFiles.copyDirectory(solrHomeSourceDir.toPath, solrHome)
    solrHome
  }

  private def createBaseDir(): Path = {
    Files.createDirectories(tmpDir.resolve("base" + System.currentTimeMillis()))
  }

  private def tmpDir: Path = Paths.get(System.getProperty("java.io.tmpdir"))

  private def timed[T](description: String)(f: => T): T = {
    logger.info(description)
    val start = System.nanoTime()
    val res = f
    logger.info(s"$description took ${TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)} ms")
    res
  }
}

object SolrCloudRunner {

  private val logger: Logger = LoggerFactory.getLogger(classOf[SolrCloudRunner])

  def start(numServers: Int, collections: List[SolrCollection] = List.empty, defaultCollection: Option[String] = None,
            maybeZkPort: Option[Int] = None, maybeSolrHome: Option[Path] = None): SolrCloudRunner = {
    new SolrCloudRunner(numServers, collections, defaultCollection, maybeZkPort, maybeSolrHome).start()
  }

}

object BetterFiles {

  /**
    * Recursively copies the given source directory to the destination directory.
    * In contrast to commons-io or better-files, it supports directories being symbolic links
    * (as used by solr-home/collection2/conf -> ../collection1/conf/).
    * Such symbolic links / directories are completely resolved, i.e. the original link target
    * is copied to the new destination (because SolrZkClient.uploadToZK seems not be able to upload
    * from a symbolic link (conf dir)).
    */
  def copyDirectory(from: Path, to: Path): Path = Files.walkFileTree(from, new CopyFileVisitor(to))

  import java.nio.file.FileVisitResult
  import java.nio.file.SimpleFileVisitor
  import java.nio.file.attribute.BasicFileAttributes

  private class CopyFileVisitor(val targetPath: Path) extends SimpleFileVisitor[Path] {

    private var sourcePath: Path = _

    override def preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult = {
      if (sourcePath == null) {
        sourcePath = dir
        Files.createDirectories(targetPath)
      } else {
        Files.createDirectories(targetPath.resolve(sourcePath.relativize(dir)))
      }
      FileVisitResult.CONTINUE
    }

    override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
      val targetFile = targetPath.resolve(sourcePath.relativize(file))
      if (attrs.isSymbolicLink) {
        // since SolrZkClient.uploadToZK seems not be able to upload from a symbolic link (conf dir), we're copying
        // the link target directory instead of copying/creating the (relative) symlink.
        // (file.toRealPath resolves the symbolic link)
        BetterFiles.copyDirectory(file.toRealPath(), targetFile)
        // if we'd like to create the symlink, this would look like this: Files.createSymbolicLink(targetFile, Files.readSymbolicLink(file))
      } else {
        Files.copy(file, targetFile)
      }
      FileVisitResult.CONTINUE
    }

  }

}
