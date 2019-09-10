package systems.opalia.service.http.impl.akka

import akka.osgi.BundleDelegatingClassLoader
import org.osgi.framework.BundleContext
import org.osgi.service.component.annotations._
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import systems.opalia.interfaces.logging.LoggingService
import systems.opalia.interfaces.soa.ConfigurationService
import systems.opalia.interfaces.soa.osgi.ServiceManager
import systems.opalia.interfaces.vfs.VfsService
import systems.opalia.service.http.api._


@Component(service = Array(classOf[HttpService]), immediate = true)
class HttpServiceImpl
  extends HttpService {

  private val serviceManager: ServiceManager = new ServiceManager()
  private var bootable: HttpServiceBootable = _

  @Reference
  private var loggingService: LoggingService = _

  @Reference
  private var vfsService: VfsService = _

  @Activate
  def start(bundleContext: BundleContext): Unit = {

    val classLoader =
      BundleDelegatingClassLoader(bundleContext, Some(Thread.currentThread().getContextClassLoader))

    val configurationService = serviceManager.getService(bundleContext, classOf[ConfigurationService])
    val config = new BundleConfig(configurationService.getConfiguration)

    bootable =
      new HttpServiceBootable(
        config,
        classLoader,
        loggingService,
        vfsService
      )

    bootable.setup()
    Await.result(bootable.awaitUp(), Duration.Inf)
  }

  @Deactivate
  def stop(bundleContext: BundleContext): Unit = {

    bootable.shutdown()
    Await.result(bootable.awaitUp(), Duration.Inf)

    serviceManager.ungetServices(bundleContext)

    bootable = null
  }

  def newRoute(path: String,
               method: Method,
               entityRequirement: EntityRequirement,
               fetch: (ServerRequest) => Future[ServerResponse]): Route =
    bootable.newRoute(path, method, entityRequirement, fetch)

  def call(request: ClientRequest): Future[ClientResponse] =
    bootable.call(request)
}
