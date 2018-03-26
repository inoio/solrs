package io.ino.solrs

import java.io.File
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.{TimeUnit, TimeoutException}

import javax.servlet.Filter
import org.apache.commons.io.FileUtils
import org.apache.solr.client.solrj.SolrQuery
import org.apache.solr.client.solrj.embedded.{JettyConfig, JettySolrRunner}
import org.apache.solr.client.solrj.impl.HttpSolrClient
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.duration._
import scala.util.control.NonFatal

/**
  * A runner for a local Solr instance using [[org.apache.solr.client.solrj.embedded.JettySolrRunner]].
  * @param port the desired Jetty port
  * @param context the context to run in (e.g. "/solr")
  * @param extraFilters extra servlet filters to use
  * @param maybeSolrHome (optional) a Solr home dir to use, tries to locate resource /solr-home in classpath if None
  */
class SolrRunner(val port: Int,
                 val context: String,
                 extraFilters: Map[Class[_ <: Filter], String],
                 maybeSolrHome: Option[Path]) {

  import io.ino.solrs.SolrRunner._

  val url = s"http://localhost:$port$context"
  private[solrs] var jetty: JettySolrRunner = _

  // init "base" = some temp dir for this run
  val baseDir: Path = createBaseDir()

  // create a copy of the given (or default) Solr home below "base" directory
  val solrHome: Path = makeSolrHomeDirIn(baseDir)

  def start: SolrRunner = {
    import scala.collection.JavaConverters._

    if (jetty != null) {
      throw new IllegalStateException("Start can only be invoked once. You probably want to use 'stop()' + 'start()'.")
    }
    logger.info(s"Starting Solr on port $port with Solr home ${solrHome.toAbsolutePath.toString}")

    System.setProperty("solr.solr.home", solrHome.toAbsolutePath.toString)
    System.setProperty("solr.default.confdir", solrHome.resolve("/collection1/conf").toString)
    System.setProperty("solr.lock.type", "single")

    val jettyConfig = JettyConfig.builder.setContext(context).setPort(port).withFilters(extraFilters.asJava).build
    jetty = new JettySolrRunner(solrHome.toAbsolutePath.toString, jettyConfig)
    startJetty(jetty)

    this
  }

  def awaitReady(value: Long, unit: TimeUnit): SolrRunner = {
    awaitReady(Duration(value, unit))
  }

  def awaitReady(timeout: Duration): SolrRunner = {
    val solrClient = new HttpSolrClient.Builder(s"http://localhost:$port$context/collection1").build()

    def await(left: Duration): SolrRunner = {
      if(left.toMillis <= 0) {
        throw new TimeoutException(s"Solr not available after $timeout")
      }
      try {
        solrClient.query(new SolrQuery("*:*"))
        this
      } catch {
        case NonFatal(_) =>
          Thread.sleep(50)
          await(left - 50.millis)
      }
    }

    await(timeout)
  }

  def isStarted: Boolean = jetty != null && jetty.isRunning

  def stop() {
    if(isStarted) {
      logger.info(s"Stopping Solr Jetty running on port $port")
      SolrRunner.stopJetty(jetty)
      jetty = null
    }
  }

  private def createBaseDir(): Path = {
    Files.createDirectories(tmpDir.resolve("base" + System.currentTimeMillis()))
  }

  private def makeSolrHomeDirIn(baseDir: Path): Path = {
    val solrHomeSourceDir = maybeSolrHome.map(_.toFile).getOrElse(new File(getClass.getResource("/solr-home").toURI))
    val solrHomeTargetInTemp = Files.createDirectories(baseDir.resolve("solrhome"))
    FileUtils.copyDirectory(solrHomeSourceDir, solrHomeTargetInTemp.toFile)
    solrHomeTargetInTemp
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

object SolrRunner {

  private val logger: Logger = LoggerFactory.getLogger(classOf[SolrRunner])
  private var solrRunners: Map[Int,SolrRunner] = Map.empty

  val DefaultContext = "/solr"
  val DefaultExtraFilters: Map[Class[_ <: Filter], String] = Map.empty

  // start with default parameters set, for Java API
  def start(port: Int): SolrRunner = start(port, DefaultContext, DefaultExtraFilters, None)

  def start(port: Int,
            context: String = DefaultContext,
            extraFilters: Map[Class[_ <: Filter], String] = DefaultExtraFilters,
            maybeSolrHome: Option[Path] = None): SolrRunner = new SolrRunner(port, context, extraFilters, maybeSolrHome).start

  def startOnce(port: Int): SolrRunner = startOnce(port, DefaultContext, DefaultExtraFilters, None)

  /**
    * Starts Solr Jetty or returns a previously started instance.
    * Also registers a shutdown hook to shutdown Solr Jetty when the JVM exits.
    */
  def startOnce(port: Int,
                context: String = "/solr",
                extraFilters: Map[Class[_ <: Filter], String] = Map.empty,
                maybeSolrHome: Option[Path] = None): SolrRunner = {
    solrRunners.getOrElse(port, {
      val solrRunner = start(port, context, extraFilters, maybeSolrHome)
      solrRunners += port -> solrRunner

      Runtime.getRuntime.addShutdownHook(new Thread() {
        override def run() {
          solrRunner.stop()
        }
      })

      solrRunner
    })
  }

  def restartJetty(jetty: JettySolrRunner): Unit = {
    logger.info(s"Restarting Jetty on port ${jetty.getLocalPort}...")
    stopJetty(jetty)
    startJetty(jetty)
  }

  def startJetty(jetty: JettySolrRunner): Unit = {
    logger.info(s"Starting Jetty...")
    jetty.start()
  }

  def stopJetty(jetty: JettySolrRunner): Unit = {
    logger.info(s"Stopping Jetty on port ${jetty.getLocalPort}")
    Option(jetty.getSolrDispatchFilter).foreach { sdf =>
      try {
        sdf.destroy()
      } catch {
        case t: Throwable => logger.error("", t)
      }
    }
    try
      jetty.stop()
    catch {
      case _: InterruptedException =>
        logger.info("Jetty stop interrupted - should be a test caused interruption, we will try again to be sure we shutdown")
    }
    if (!jetty.isStopped) jetty.stop()
    if (!jetty.isStopped) throw new RuntimeException("Could not stop jetty")
  }

  private def tmpDir: Path = Paths.get(System.getProperty("java.io.tmpdir"))

}