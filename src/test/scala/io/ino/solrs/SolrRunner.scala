package io.ino.solrs

import org.slf4j.{LoggerFactory, Logger}
import java.io.{IOException, File}
import org.apache.catalina.startup.Tomcat
import java.net.URL
import org.apache.commons.io.FileUtils
import java.util.logging.Level
import org.apache.catalina.LifecycleState

class SolrRunner(val port: Int, private val solrHome: File, private val solrWar: File) {

  require(solrHome.exists, s"The solrHome ${solrHome.getAbsolutePath} does not exist.")
  require(solrWar.exists, s"The solrWar ${solrWar.getAbsolutePath} does not exist.")

  import SolrRunner._

  val url = s"http://localhost:$port/solr"
  private[solrs] var tomcat: Tomcat = null

  def this(port: Int) {
    this(port, SolrRunner.getDefaultSolrHome, SolrRunner.checkOrGetSolrWar)
  }

  def start: SolrRunner = {
    if (tomcat != null) {
      throw new IllegalStateException("Start can only be invoked once. You probably want to use 'restart()'.")
    }
    logger.info("Starting solr on port {} with solr home {}", port, solrHome.getAbsolutePath)
    System.setProperty("solr.solr.home", solrHome.getAbsolutePath)
    System.setProperty("solr.lock.type", "single")
    java.util.logging.Logger.getLogger("org.apache.catalina.util.LifecycleMBeanBase").setLevel(Level.SEVERE)
    tomcat = new Tomcat
    tomcat.setPort(port)
    val baseDir: File = new File(System.getProperty("java.io.tmpdir"), "solrs-tc-basedir")


    FileUtils.forceMkdir(baseDir)
    FileUtils.forceMkdir(new File(baseDir, "webapps"))
    tomcat.setBaseDir(baseDir.getAbsolutePath)
    tomcat.addWebapp("/solr", solrWar.getAbsolutePath)
    tomcat.start()
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

object SolrRunner {

  private final val solrVersion: String = "4.10.1"
  private val logger: Logger = LoggerFactory.getLogger(classOf[SolrRunner])
  private var solrRunner: Option[SolrRunner] = None

  def start(port: Int): SolrRunner = new SolrRunner(port).start

  /**
   * Starts tomcat with solr, or returns a previously started instance.
   * Also registers a shutdown hook to shutdown tomcat when the jvm exits.
   */
  def startOnce(port: Int): SolrRunner = {
    solrRunner.getOrElse {
      solrRunner = Some(start(port))

      Runtime.getRuntime().addShutdownHook(new Thread() {
        override def run() {
          solrRunner.map(_.stop())
        }
      })

      solrRunner.get
    }
  }

  def getDefaultSolrHome: File = {
    new File(classOf[SolrRunner].getResource("/solr-home").toURI())
  }

  def checkOrGetSolrWar: File = {
    try {
      val solrWar: File = new File("./.solr/solr.war")
      if (!solrWar.exists) {
        logger.info(s"Downloading solr.war to ${solrWar.getAbsolutePath}")
        val source: URL = new URL(s"http://repo1.maven.org/maven2/org/apache/solr/solr/$solrVersion/solr-$solrVersion.war")
        FileUtils.copyURLToFile(source, solrWar)
        logger.info("Finished downloading solr.war")
      }
      solrWar
    }
    catch {
      case e: IOException => throw new RuntimeException(e)
    }
  }

}