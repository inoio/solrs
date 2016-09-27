package io.ino.solrs

import java.io.File
import java.net.URL
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.logging.Level

import org.apache.catalina.Context
import org.apache.catalina.LifecycleState
import org.apache.catalina.startup.Tomcat
import org.apache.commons.io.FileUtils
import org.apache.curator.test.TestingServer
import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.impl.HttpSolrClient
import org.apache.solr.common.cloud.ZkStateReader
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.util.control.NonFatal

case class ZooKeeperOptions(zkHost: String, bootstrapConfig: Option[String] = None)

class SolrRunner(val port: Int,
                 private val solrHome: File,
                 private val solrWar: File,
                 maybeZkOptions: Option[ZooKeeperOptions]) {

  def this(port: Int, zkOptions: Option[ZooKeeperOptions] = None) {
    this(port, SolrRunner.initSolrHome(port), SolrRunner.solrWar, zkOptions)
  }

  require(solrHome.exists, s"The solrHome ${solrHome.getAbsolutePath} does not exist.")
  require(solrWar.exists, s"The solrWar ${solrWar.getAbsolutePath} does not exist.")

  import io.ino.solrs.SolrRunner._

  val url = s"http://localhost:$port/solr"
  private[solrs] var tomcat: Tomcat = _

  private[solrs] var context: Context = _

  def start: SolrRunner = {
    if (tomcat != null) {
      throw new IllegalStateException("Start can only be invoked once. You probably want to use 'restart()'.")
    }
    logger.info("Starting solr on port {} with solr home {}", port, solrHome.getAbsolutePath)
    System.setProperty("solr.solr.home", solrHome.getAbsolutePath)
    System.setProperty("solr.lock.type", "single")
    java.util.logging.Logger.getLogger("org.apache.catalina.util.LifecycleMBeanBase").setLevel(Level.SEVERE)

    // ZooKeeper / SolrCloud support
    processZkOptionsBeforeStart()

    tomcat = new Tomcat
    tomcat.setPort(port)
    val baseDir = tmpDir.resolve(s"solrs-tc-basedir.$port")

    Files.createDirectories(baseDir)
    Files.createDirectories(baseDir.resolve("webapps"))

    // maybe we already had solr expanded, so let's remove this because we might have
    // downloaded a new solr version
    FileUtils.deleteDirectory(baseDir.resolve("webapps").resolve("solr").toFile)

    tomcat.setBaseDir(baseDir.toAbsolutePath.toString)
    context = tomcat.addWebapp("/solr", solrWar.getAbsolutePath)
    // context.asInstanceOf[StandardContext].setClearReferencesStopThreads(true)
    tomcat.start()

    // Cleanup Zk stuff
    processZkOptionsAfterStart()

    this
  }

  def awaitReady(value: Long, unit: TimeUnit): SolrRunner = {
    awaitReady(Duration(value, unit))
  }

  def awaitReady(timeout: Duration): SolrRunner = {
    val solrClient = new HttpSolrClient(s"http://localhost:$port/solr/collection1")

    def await(left: Duration): SolrRunner = {
      if(left.toMillis <= 0) {
        throw new TimeoutException(s"Solr not available after $timeout")
      }
      try {
        solrClient.query(new SolrQuery("*:*"))
        this
      } catch {
        case NonFatal(e) =>
          Thread.sleep(50)
          await(left - 50.millis)
      }
    }

    await(timeout)
  }

  protected def processZkOptionsBeforeStart(): Unit = {
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
  }

  /* Reset optional zk system properties, so that they do not affect other SolrRunners started later
   */
  protected def processZkOptionsAfterStart(): Unit = {
    maybeZkOptions.foreach{ zkOptions =>
      System.clearProperty("zkHost")
      zkOptions.bootstrapConfig.foreach { _ =>
        System.clearProperty("bootstrap_confdir")
        System.clearProperty("collection.configName")
      }
    }
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

  /**
   * The (downstripped) solr war, is the solr.war extracted from the solr distribution with
   * WEB-INF/libs removed (because they should all be in the classpath).
   */
  lazy val solrWar = {
    // when loaded from a jar getResource("/solr.war") returns s.th. like
    // jar:file:/home/magro/.ivy2/local/io.ino/solrs_2.11/1.0.2/jars/solrs_2.11-tests.jar!/solr.war
    val warUrl = classOf[SolrRunner].getResource("/solr.war")
    if(warUrl.getProtocol == "file") {
      new File(warUrl.toURI)
    } else if(warUrl.getProtocol == "jar") {
      // copy the war file to tmp dir
      val solrsVersion = getJarManifestVersion(warUrl)
      val tmpWarFile = tmpDir.resolve("solrs").resolve(s"solr-$solrsVersion.war")
      if(!tmpWarFile.toFile.exists()) {
        if(!tmpWarFile.getParent.toFile.exists()) {
          Files.createDirectories(tmpWarFile.getParent)
        }
        Files.copy(classOf[SolrRunner].getResourceAsStream("/solr.war"), tmpWarFile)
      }
      tmpWarFile.toFile
    } else {
      throw new UnsupportedOperationException(s"Cannot load solr war from URL $warUrl")
    }
  }

  private def getJarManifestVersion(warUrl: URL): String = {
    val jarPath = warUrl.getPath().substring(5, warUrl.getPath().indexOf("!"))
    val jar = new JarFile(URLDecoder.decode(jarPath, "UTF-8"))
    jar.getManifest.getMainAttributes.getValue(Attributes.Name.IMPLEMENTATION_VERSION)
  }

  private val logger: Logger = LoggerFactory.getLogger(classOf[SolrRunner])
  private var solrRunners: Map[Int,SolrRunner] = Map.empty

  def start(port: Int, zkOptions: Option[ZooKeeperOptions] = None): SolrRunner = new SolrRunner(port, zkOptions).start

  /**
   * Starts tomcat with solr, or returns a previously started instance.
   * Also registers a shutdown hook to shutdown tomcat when the jvm exits.
   */
  def startOnce(port: Int, zkOptions: Option[ZooKeeperOptions] = None): SolrRunner = {
    solrRunners.getOrElse(port, {
      val solrRunner = start(port, zkOptions)
      solrRunners += port -> solrRunner

      Runtime.getRuntime.addShutdownHook(new Thread() {
        override def run() {
          solrRunner.stop()
        }
      })

      solrRunner
    })
  }

  private def initSolrHome(port: Int): File = {
    val template = new File(classOf[SolrRunner].getResource("/solr-home").toURI)
    val solrHome = new File(template.getParentFile, s"solr-home_$port")
    FileUtils.copyDirectory(template, solrHome)
    solrHome
  }

  private def tmpDir: Path = Paths.get(System.getProperty("java.io.tmpdir"))

}