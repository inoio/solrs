package io.ino.solrs

import java.net.URL
import java.util.logging.Level
import org.apache.curator.test.TestingServer
import org.apache.solr.common.cloud.ZkStateReader
import org.slf4j.{LoggerFactory, Logger}
import java.io.{IOException, File}
import java.nio.file.{Files, Paths}
import org.apache.catalina.startup.Tomcat
import org.apache.catalina.LifecycleState
import org.apache.commons.io.FileUtils

case class ZooKeeperOptions(zkHost: String, bootstrapConfig: Option[String] = None)

class SolrRunner(val port: Int,
                 private val solrHome: File,
                 private val solrWar: File,
                 maybeZkOptions: Option[ZooKeeperOptions] = None) {

  require(solrHome.exists, s"The solrHome ${solrHome.getAbsolutePath} does not exist.")
  require(solrWar.exists, s"The solrWar ${solrWar.getAbsolutePath} does not exist.")

  import SolrRunner._

  val url = s"http://localhost:$port/solr"
  private[solrs] var tomcat: Tomcat = null

  def this(port: Int, zkOptions: Option[ZooKeeperOptions] = None) {
    this(port, SolrRunner.initSolrHome(port), SolrRunner.checkOrGetSolrWar, zkOptions)
  }

  def start: SolrRunner = {
    if (tomcat != null) {
      throw new IllegalStateException("Start can only be invoked once. You probably want to use 'restart()'.")
    }
    logger.info("Starting solr on port {} with solr home {}", port, solrHome.getAbsolutePath)
    System.setProperty("solr.solr.home", solrHome.getAbsolutePath)
    System.setProperty("solr.lock.type", "single")
    java.util.logging.Logger.getLogger("org.apache.catalina.util.LifecycleMBeanBase").setLevel(Level.SEVERE)

    // ZooKeeper / SolrCloud support
    maybeZkOptions.foreach { zkOptions =>

      zkOptions.bootstrapConfig.map { conf =>

        val bootstrapConfDir = solrHome.toPath.toAbsolutePath.resolve(conf).resolve("conf").toString
        logger.info(s"Starting with bootstrap confdir $bootstrapConfDir")

        /* Since we don't yet have a config in zookeeper, this parameter causes the local configuration directory ./solr/conf to be
         * uploaded as the "myconf" config. The name "myconf" is taken from the "collection.configName" param below. */
        System.setProperty("bootstrap_confdir", bootstrapConfDir)
        /* sets the config to use for the new collection. Omitting this param will cause the config name to default to "configuration1". */
        System.setProperty("collection.configName", s"${conf}Conf")

      }
      /*  points to the Zookeeper ensemble containing the cluster state. */
      System.setProperty("zkHost", zkOptions.zkHost)
      /* Set host port used for zookeeper registration, as specified in solr.xml */
      System.setProperty("host.port", port.toString)

    }

    tomcat = new Tomcat
    tomcat.setPort(port)
    val baseDir = Paths.get(System.getProperty("java.io.tmpdir"), s"solrs-tc-basedir.$port")

    Files.createDirectories(baseDir)
    Files.createDirectories(baseDir.resolve("webapps"))

    // maybe we already had solr expanded, so let's remove this because we might have
    // downloaded a new solr version
    FileUtils.deleteDirectory(baseDir.resolve("webapps").resolve("solr").toFile)

    tomcat.setBaseDir(baseDir.toAbsolutePath.toString)
    val context = tomcat.addWebapp("/solr", solrWar.getAbsolutePath)
    // context.asInstanceOf[StandardContext].setClearReferencesStopThreads(true)
    tomcat.start()

    /* Reset optional zk system properties, so that they do not affect other SolrRunners started later
     */
    maybeZkOptions.foreach{ zkOptions =>
      System.clearProperty("zkHost")
      zkOptions.bootstrapConfig.foreach { _ =>
        System.clearProperty("bootstrap_confdir")
        System.clearProperty("collection.configName")
      }
    }

    this
  }

  def isStarted: Boolean = tomcat != null && tomcat.getServer.getState == LifecycleState.STARTED
  def isStopped: Boolean = tomcat != null && tomcat.getServer.getState == LifecycleState.STOPPED
  def isDestroyed: Boolean = tomcat == null

  def stop() {
    if(isStarted) {
      logger.info("Stopping solr/tomcat running on port {}", port)
      tomcat.stop()
      tomcat.destroy()
      tomcat = null
    }
  }

  def canEqual(other: Any): Boolean = other.isInstanceOf[SolrRunner]

  override def equals(other: Any): Boolean = other match {
    case that: SolrRunner =>
      (that canEqual this) &&
        url == that.url
    case _ => false
  }

  override def hashCode(): Int = {
    val state = Seq(url)
    state.map(_.hashCode()).foldLeft(0)((a, b) => 31 * a + b)
  }
}

object RunSolr extends App {

  val solrRunner = new SolrRunner(8888).start
  try {
    Thread.sleep(Long.MaxValue)
  }
  catch {
    case e: InterruptedException => solrRunner.stop()
  }
}


object RunSolrCloud extends App {

  val testServer = new TestingServer()
  println("Connection: " + testServer.getConnectString)
  testServer.start()
  println("Started ZooKeeper on port " + testServer.getPort)

  val solrRunner1 = SolrRunner.start(8888, Some(ZooKeeperOptions(testServer.getConnectString, bootstrapConfig = Some("collection1"))))
  val solrRunner2 = SolrRunner.start(8889, Some(ZooKeeperOptions(testServer.getConnectString)))

  val zk: ZkStateReader = new ZkStateReader(testServer.getConnectString, /*zkClientTimeout =*/ 1000, /*zkConnectTimeout =*/ 1000)
  try {
    zk.createClusterStateWatchersAndUpdate
  } catch {
    case e: Throwable => println("Caught " + e)
  }

  import scala.collection.JavaConversions._
  println("Started ZkStateReader, read cluster props: " + zk.getClusterProps.mkString(", ") + ", cluster state: " + zk.getClusterState)

  /*
  solrRunner1.stop()

  Thread.sleep(2000)
  println("Started ZkStateReader, read cluster props: " + zk.getClusterProps.mkString(", ") + ", cluster state: " + zk.getClusterState)

  val solrRunner3 = SolrRunner.start(8888, Some(ZooKeeperOptions(testServer.getConnectString)))

  Thread.sleep(2000)
  println("Started ZkStateReader, read cluster props: " + zk.getClusterProps.mkString(", ") + ", cluster state: " + zk.getClusterState)

  val solrRunner4 = SolrRunner.start(8890, Some(ZooKeeperOptions(testServer.getConnectString)))

  Thread.sleep(2000)
  println("Started ZkStateReader, read cluster props: " + zk.getClusterProps.mkString(", ") + ", cluster state: " + zk.getClusterState)
*/
}

object SolrRunner {

  private final val solrVersion: String = "4.10.1"
  private val logger: Logger = LoggerFactory.getLogger(classOf[SolrRunner])
  private var solrRunners: Map[Int,SolrRunner] = Map.empty

  def start(port: Int, zkOptions: Option[ZooKeeperOptions] = None): SolrRunner = new SolrRunner(port, zkOptions).start

  /**
   * Starts tomcat with solr, or returns a previously started instance.
   * Also registers a shutdown hook to shutdown tomcat when the jvm exits.
   */
  def startOnce(port: Int, zkOptions: Option[ZooKeeperOptions] = None): SolrRunner = {
    solrRunners.get(port).getOrElse {
      val solrRunner = start(port, zkOptions)
      solrRunners += port -> solrRunner

      Runtime.getRuntime().addShutdownHook(new Thread() {
        override def run() {
          solrRunner.stop()
        }
      })

      solrRunner
    }
  }

  def initSolrHome(port: Int): File = {
    val template = new File(classOf[SolrRunner].getResource("/solr-home").toURI())
    val solrHome = new File(template.getParentFile, s"solr-home_$port")
    FileUtils.copyDirectory(template, solrHome)
    solrHome
  }

  def checkOrGetSolrWar: File = {
    try {
      val solrWar: File = new File(s"./.solr/solr-$solrVersion.war")
      if (!solrWar.exists) {
        logger.info(s"Downloading solr.war to ${solrWar.getAbsolutePath}")
        val source: URL = new URL(s"http://repo1.maven.org/maven2/org/apache/solr/solr/$solrVersion/solr-$solrVersion.war")
        FileUtils.copyURLToFile(source, solrWar)
        logger.info(s"Finished downloading solr-$solrVersion.war")
      }
      solrWar
    }
    catch {
      case e: IOException => throw new RuntimeException(e)
    }
  }

}