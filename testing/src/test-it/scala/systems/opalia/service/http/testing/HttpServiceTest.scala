package systems.opalia.service.http.testing

import com.typesafe.config.Config
import play.api.libs.json.Json
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.postfixOps
import systems.opalia.bootloader.ArtifactNameBuilder._
import systems.opalia.bootloader.BootloaderBuilder
import systems.opalia.commons.codec.Base64
import systems.opalia.commons.configuration.ConfigHelper._
import systems.opalia.commons.configuration.Reader._
import systems.opalia.commons.identifier.ObjectId
import systems.opalia.commons.io.FileUtils
import systems.opalia.commons.json.JsonAstTransformer
import systems.opalia.commons.net.{EndpointAddress, Uri}
import systems.opalia.interfaces.rendering.Renderer
import systems.opalia.interfaces.vfs.VfsService
import systems.opalia.service.http.api._
import systems.opalia.service.http.api.errors._
import systems.opalia.service.http.testing.helper.AbstractTest


class HttpServiceTest
  extends AbstractTest {

  var httpService: HttpService = _
  var vfsService: VfsService = _

  var server: EndpointAddress = _

  def testName: String =
    "http-service-test"

  def configure(bootBuilder: BootloaderBuilder): BootloaderBuilder = {

    bootBuilder
      .withExtraExportPackage("systems.opalia.service.http.api.*")
      .withBundle("systems.opalia" %% "logging-impl-logback" % "1.0.0")
      .withBundle("systems.opalia" %% "vfs-backend-api" % "1.0.0")
      .withBundle("systems.opalia" %% "vfs-backend-impl-apachevfs" % "1.0.0")
      .withBundle("systems.opalia" %% "vfs-impl-frontend" % "1.0.0")
      .withBundle("systems.opalia" %% "http-impl-akka" % "1.0.0")
  }

  def init(config: Config): Unit = {

    httpService = serviceManager.getService(bundleContext, classOf[HttpService])
    vfsService = serviceManager.getService(bundleContext, classOf[VfsService])
    server = config.as[EndpointAddress]("http.server")
  }

  it should "be able to match paths correctly" in {

    val htmlContentType = "text/html; charset=UTF-8"
    val htmlContent = """<!DOCTYPE html><html><head><meta charset="utf-8"></head><body>Hello World!</body></html>"""

    def echo(request: ServerRequest): Future[ServerResponse] =
      request.handleEntity().map {
        entity =>

          OkServerResponse(entity = entity)
      }

    def htmlAnswer(request: ServerRequest): Future[ServerResponse] =
      request.handleEntity().map {
        _ =>

          OkServerResponse(entity = CharacterEntity(contentType = htmlContentType, body = htmlContent))
      }

    val uri1 =
      Uri(
        scheme = "http",
        authority = Some(Uri.Authority(server.hostString, server.port)),
        path = Uri.Path("/dir/foo", Uri.Path.Type.Regular)
      )

    val uri2 =
      Uri(
        scheme = "http",
        authority = Some(Uri.Authority(server.hostString, server.port)),
        path = Uri.Path("/dir/bar", Uri.Path.Type.Regular)
      )

    // create routes
    val route1 = httpService.newRoute(uri1.path.toString, Method.get, EntityRequirement.None, echo)
    val route2 = httpService.newRoute(uri1.path.toString, Method.post, EntityRequirement.None, htmlAnswer)
    val route3 = httpService.newRoute(uri2.path.toString, Method.get, EntityRequirement.None, echo)
    val route4 = httpService.newRoute(uri1.path.toString, Method.get, EntityRequirement.None, echo)

    // register routes
    route1.register()
    route2.register()
    route3.register()
    an[IllegalArgumentException] should be thrownBy route4.register()

    {
      val result =
        Await.result(httpService.call(ClientRequest(
          uri = uri1.toString,
          method = Method.get,
          entity = NoneEntity
        )), 4 seconds)

      result.statusCode shouldBe 200
      Await.result(result.handleEntity(EntityRequirement.None), 4 seconds) shouldBe NoneEntity
    }

    {
      val result =
        Await.result(httpService.call(ClientRequest(
          uri = uri1.toString,
          method = Method.post,
          entity = NoneEntity
        )), 4 seconds)

      result.statusCode shouldBe 200

      Await.result(result.handleEntity(EntityRequirement.Characters), 4 seconds) shouldBe
        CharacterEntity(contentType = htmlContentType, body = htmlContent)
    }

    {
      val result =
        Await.result(httpService.call(ClientRequest(
          uri = uri2.toString,
          method = Method.get,
          entity = NoneEntity
        )), 4 seconds)

      result.statusCode shouldBe 200
      Await.result(result.handleEntity(EntityRequirement.None), 4 seconds) shouldBe NoneEntity
    }

    {
      val result =
        Await.result(httpService.call(ClientRequest(
          uri = uri2.toString,
          method = Method.post,
          entity = NoneEntity
        )), 4 seconds)

      result.statusCode shouldBe 404
      Await.result(result.handleEntity(EntityRequirement.Json), 4 seconds) shouldBe a[JsonEntity]
    }

    // unregister routes
    route1.unregister()
    route2.unregister()
    route3.unregister()
    an[IllegalArgumentException] should be thrownBy route4.unregister()
  }

  it should "be able to handle entities correctly" in {

    // echo with entity copy

    def echoNoBody(request: ServerRequest): Future[ServerResponse] =
      request.handleEntity().map {
        entity =>

          request.headers.exists(_._1 == "X-Content-Checksum") shouldBe false

          OkServerResponse(entity = entity)
      }

    def echo(request: ServerRequest): Future[ServerResponse] =
      request.handleEntity().map {
        entity =>

          request.headers.exists(_._1 == "X-Content-Checksum") shouldBe true

          OkServerResponse(entity = entity)
      }

    // create URIs for all routes

    val uri1 =
      Uri(
        scheme = "http",
        authority = Some(Uri.Authority(server.hostString, server.port)),
        path = Uri.Path("/a", Uri.Path.Type.Regular)
      )

    val uri2 =
      Uri(
        scheme = "http",
        authority = Some(Uri.Authority(server.hostString, server.port)),
        path = Uri.Path("/b", Uri.Path.Type.Regular)
      )

    val uri3 =
      Uri(
        scheme = "http",
        authority = Some(Uri.Authority(server.hostString, server.port)),
        path = Uri.Path("/c", Uri.Path.Type.Regular)
      )

    val uri4 =
      Uri(
        scheme = "http",
        authority = Some(Uri.Authority(server.hostString, server.port)),
        path = Uri.Path("/d", Uri.Path.Type.Regular)
      )

    val uri5 =
      Uri(
        scheme = "http",
        authority = Some(Uri.Authority(server.hostString, server.port)),
        path = Uri.Path("/e", Uri.Path.Type.Regular)
      )

    val uri6 =
      Uri(
        scheme = "http",
        authority = Some(Uri.Authority(server.hostString, server.port)),
        path = Uri.Path("/f", Uri.Path.Type.Regular)
      )

    // create routes for all entity types
    val route1 = httpService.newRoute(uri1.path.toString, Method.get, EntityRequirement.None, echoNoBody)
    val route2 = httpService.newRoute(uri2.path.toString, Method.get, EntityRequirement.Json, echo)
    val route3 = httpService.newRoute(uri3.path.toString, Method.get, EntityRequirement.Characters, echo)
    val route4 = httpService.newRoute(uri4.path.toString, Method.get, EntityRequirement.Binary, echo)
    val route5 = httpService.newRoute(uri5.path.toString, Method.get, EntityRequirement.Asset, echo)
    val route6 = httpService.newRoute(uri6.path.toString, Method.get, EntityRequirement.Multipart, echo)

    // register routes
    route1.register()
    route2.register()
    route3.register()
    route4.register()
    route5.register()
    route6.register()

    // create test data

    val jsonEntity =
      JsonEntity(JsonAstTransformer.fromPlayJsonObject(Json.obj("one" -> 42, "two" -> "This is a text.")))

    val characterEntity =
      CharacterEntity(body = "This is the test message.")

    val binaryEntity =
      BinaryEntity(body = Vector(42, 73, 0))

    val fs = vfsService.getFileSystem("asset")
    val id = new Array[Byte](ObjectId.length)

    {
      val fileName = "index.html"
      val contentType = "text/html; charset=UTF-8"
      val content = """<!DOCTYPE html><html><head><meta charset="utf-8"></head><body>Hello World!</body></html>"""

      FileUtils.using(fs.create(id, fileName, contentType)) {
        outputStream =>

          outputStream.write(content.getBytes(Renderer.appDefaultCharset))
      }

      fs.commit(id)
    }

    val assetEntity = AssetEntity(id)

    val multipartEntity =
      MultipartEntity(Map(
        "_01" -> jsonEntity,
        "_02" -> characterEntity,
        "_03" -> binaryEntity,
        "_04" -> assetEntity,
      ))

    // send entities to echo server and compare with original

    {
      val request =
        ClientRequest(
          uri = uri1.toString,
          method = Method.get,
          entity = NoneEntity
        )

      val response = Await.result(httpService.call(request), 4 seconds)

      response.statusCode shouldBe 200
      response.headers.exists(_._1 == "X-Content-Checksum") shouldBe false

      Await.result(response.handleEntity(EntityRequirement.None), 4 seconds) shouldBe NoneEntity
    }

    {
      val request =
        ClientRequest(
          uri = uri2.toString,
          method = Method.get,
          entity = jsonEntity
        )

      val response = Await.result(httpService.call(request), 4 seconds)

      response.statusCode shouldBe 200
      response.headers.exists(_._1 == "X-Content-Checksum") shouldBe true

      Await.result(response.handleEntity(EntityRequirement.Json).mapTo[JsonEntity], 4 seconds) shouldBe jsonEntity
    }

    {
      val request =
        ClientRequest(
          uri = uri3.toString,
          method = Method.get,
          entity = characterEntity
        )

      val response = Await.result(httpService.call(request), 4 seconds)

      response.statusCode shouldBe 200
      response.headers.exists(_._1 == "X-Content-Checksum") shouldBe true

      Await.result(response.handleEntity(EntityRequirement.Characters), 4 seconds) shouldBe characterEntity
    }

    {
      val request =
        ClientRequest(
          uri = uri4.toString,
          method = Method.get,
          entity = binaryEntity
        )

      val response = Await.result(httpService.call(request), 4 seconds)

      response.statusCode shouldBe 200
      response.headers.exists(_._1 == "X-Content-Checksum") shouldBe true

      Await.result(response.handleEntity(EntityRequirement.Binary), 4 seconds) shouldBe binaryEntity
    }

    {
      val request =
        ClientRequest(
          uri = uri5.toString,
          method = Method.get,
          entity = assetEntity
        )

      val response = Await.result(httpService.call(request), 4 seconds)

      response.statusCode shouldBe 200
      response.headers.exists(_._1 == "X-Content-Checksum") shouldBe true

      val assetEntityCopy =
        Await.result(response.handleEntity(EntityRequirement.Asset).mapTo[AssetEntity], 4 seconds)

      val fileContentCopy = fs.fetchFileContent(assetEntityCopy.id)

      fileContentCopy.checksum shouldBe fs.fetchFileContent(id).checksum
    }

    {
      val request =
        ClientRequest(
          uri = uri6.toString,
          method = Method.get,
          entity = multipartEntity
        )

      val response = Await.result(httpService.call(request), 4 seconds)

      response.statusCode shouldBe 200
      response.headers.exists(_._1 == "X-Content-Checksum") shouldBe true

      val multipartEntityCopy =
        Await.result(response.handleEntity(EntityRequirement.Multipart).mapTo[MultipartEntity], 4 seconds)

      multipartEntityCopy.body.keySet shouldBe multipartEntity.body.keySet
      multipartEntityCopy.body("_01").asInstanceOf[JsonEntity] shouldBe jsonEntity
      multipartEntityCopy.body("_02") shouldBe characterEntity
      multipartEntityCopy.body("_03") shouldBe binaryEntity

      val assetEntityCopy = multipartEntityCopy.body("_04").asInstanceOf[AssetEntity]
      val fileContentCopy = fs.fetchFileContent(assetEntityCopy.id)

      fileContentCopy.checksum shouldBe fs.fetchFileContent(id).checksum
      fileContentCopy.contentType shouldBe fs.fetchFileContent(id).contentType
      fileContentCopy.fileName shouldBe fs.fetchFileContent(id).fileName
      fileContentCopy.fileSize shouldBe fs.fetchFileContent(id).fileSize
    }

    // unregister routes
    route1.unregister()
    route2.unregister()
    route3.unregister()
    route4.unregister()
    route5.unregister()
    route6.unregister()
  }

  it should "be possible for client and server to make a basic access authentication" in {

    def createAuthorizationHeader(username: String, password: String): (String, String) = {

      "Authorization" -> s"Basic ${Base64.encodeFromString(s"$username:$password")}"
    }

    def getUsernameAndPassword(headers: Map[String, String]): Option[(String, String)] = {

      val pattern = """^Basic\s+(.+)$""".r

      headers
        .get("Authorization")
        .flatMap {
          case pattern(x) =>
            Base64.decodeToStringOpt(x)
              .map(x => x.split(":"))
              .map(x => (x(0), x(1)))
          case _ => None
        }
    }

    val username = "username"
    val password = "password"

    def fetch(request: ServerRequest): Future[ServerResponse] =
      Future {

        if (!getUsernameAndPassword(request.headers).exists(x => x._1 == username && x._2 == password))
          throw new UnauthorizedHttpException(ServerErrorData(
            headers = Map("WWW-Authenticate" -> "Basic realm=\"Access to protected content\""),
            messages = List("Access Denied.")
          ))

        OkServerResponse(entity = CharacterEntity(body = "This is the test message."))
      }

    val route = httpService.newRoute("/auth", Method.get, EntityRequirement.None, fetch)

    route.register()

    val uri =
      Uri(
        scheme = "http",
        authority = Some(Uri.Authority(server.hostString, server.port)),
        path = Uri.Path("/auth", Uri.Path.Type.Regular)
      )

    val request1 =
      ClientRequest(
        uri = uri.toString,
        method = Method.get
      )

    val request2 =
      ClientRequest(
        uri = uri.toString,
        method = Method.get,
        headers = Map.empty + createAuthorizationHeader(username, password)
      )

    val response1 =
      httpService.call(request1)

    val response2 =
      httpService.call(request2)

    val result1 = Await.result(for {
      x <- response1
      y <- x.handleEntity(EntityRequirement.Json).mapTo[JsonEntity]
    } yield (x, y), 4 seconds)

    val result2 = Await.result(for {
      x <- response2
      y <- x.handleEntity(EntityRequirement.Characters).mapTo[CharacterEntity]
    } yield (x, y), 4 seconds)

    result1._1.statusCode shouldBe 401
    result1._1.headers.toMap.get("WWW-Authenticate") shouldBe Some("Basic realm=\"Access to protected content\"")

    result2._1.statusCode shouldBe 200
    result2._2.contentType shouldBe "text/plain; charset=UTF-8"
    result2._2.body shouldBe "This is the test message."

    route.unregister()
  }

  it should "be possible for client and server to make an authentication with bearer token" in {

    def createAuthorizationHeader(token: ObjectId): (String, String) = {

      "Authorization" -> s"Bearer $token"
    }

    def getToken(headers: Map[String, String]): Option[ObjectId] = {

      val pattern = """^Bearer\s+(.+)$""".r

      headers
        .get("Authorization")
        .flatMap {
          case pattern(x) => ObjectId.getFromOpt(x)
          case _ => None
        }
    }

    val userId = ObjectId.getNew

    def fetch(request: ServerRequest): Future[ServerResponse] =
      Future {

        if (!getToken(request.headers).contains(userId))
          throw new UnauthorizedHttpException(ServerErrorData(
            messages = List("Access Denied.")
          ))

        OkServerResponse(entity = CharacterEntity(body = "This is the test message."))
      }

    val route = httpService.newRoute("/auth", Method.get, EntityRequirement.None, fetch)

    route.register()

    val uri =
      Uri(
        scheme = "http",
        authority = Some(Uri.Authority(server.hostString, server.port)),
        path = Uri.Path("/auth", Uri.Path.Type.Regular)
      )

    val request1 =
      ClientRequest(
        uri = uri.toString,
        method = Method.get
      )

    val request2 =
      ClientRequest(
        uri = uri.toString,
        method = Method.get,
        headers = Map.empty + createAuthorizationHeader(userId)
      )

    val response1 =
      httpService.call(request1)

    val response2 =
      httpService.call(request2)

    val result1 = Await.result(for {
      x <- response1
      y <- x.handleEntity(EntityRequirement.Json).mapTo[JsonEntity]
    } yield (x, y), 4 seconds)

    val result2 = Await.result(for {
      x <- response2
      y <- x.handleEntity(EntityRequirement.Characters).mapTo[CharacterEntity]
    } yield (x, y), 4 seconds)

    result1._1.statusCode shouldBe 401

    result2._1.statusCode shouldBe 200
    result2._2.contentType shouldBe "text/plain; charset=UTF-8"
    result2._2.body shouldBe "This is the test message."

    route.unregister()
  }

  it should "be possible for client and server to use entity tags" in {

    def createIfNoneMatchHeader(tag: ObjectId): (String, String) = {

      "If-None-Match" -> ("\"" + tag.toString + "\"")
    }

    def getEntityTag(headers: Seq[(String, String)], clientSide: Boolean): Option[ObjectId] = {

      val pattern = """^\"(.+)\"$""".r

      val tagOpt =
        if (clientSide)
          headers.find(_._1 == "ETag").map(_._2)
        else
          headers.find(_._1 == "If-None-Match").map(_._2)

      tagOpt
        .flatMap {
          case pattern(x) => ObjectId.getFromOpt(x)
          case _ => None
        }
    }

    val entityId = ObjectId.getNew

    def fetch(request: ServerRequest): Future[ServerResponse] =
      Future {

        if (!getEntityTag(request.headers.toSeq, clientSide = false).contains(entityId)) {

          OkServerResponse(
            entityTag = Some(EntityTag(entityId.toString)),
            entity = CharacterEntity(body = "This is the test message.")
          )

        } else {

          NotModifiedServerResponse(entityTag = EntityTag(entityId.toString))
        }
      }

    val route = httpService.newRoute("/resource", Method.get, EntityRequirement.None, fetch)

    route.register()

    val uri =
      Uri(
        scheme = "http",
        authority = Some(Uri.Authority(server.hostString, server.port)),
        path = Uri.Path("/resource", Uri.Path.Type.Regular)
      )

    val request1 =
      ClientRequest(
        uri = uri.toString,
        method = Method.get
      )

    val request2 =
      ClientRequest(
        uri = uri.toString,
        method = Method.get,
        headers = Map.empty + createIfNoneMatchHeader(entityId)
      )

    val response1 =
      httpService.call(request1)

    val response2 =
      httpService.call(request2)

    val result1 = Await.result(for {
      x <- response1
      y <- x.handleEntity(EntityRequirement.Characters).mapTo[CharacterEntity]
    } yield (x, y), 4 seconds)

    val result2 = Await.result(for {
      x <- response2
      y <- x.handleEntity(EntityRequirement.None)
    } yield (x, y), 4 seconds)

    result1._1.statusCode shouldBe 200
    getEntityTag(result1._1.headers, clientSide = true) shouldBe Some(entityId)
    result1._2.contentType shouldBe "text/plain; charset=UTF-8"
    result1._2.body shouldBe "This is the test message."

    result2._1.statusCode shouldBe 304
    getEntityTag(result2._1.headers, clientSide = true) shouldBe Some(entityId)

    route.unregister()
  }

  it should "be able to handle 404 errors correctly" in {

    def fetch(request: ServerRequest): Future[ServerResponse] =
      Future {

        OkServerResponse()
      }

    val route = httpService.newRoute("/resource", Method.get, EntityRequirement.None, fetch)

    route.register()

    val uri1 =
      Uri(
        scheme = "http",
        authority = Some(Uri.Authority(server.hostString, server.port)),
        path = Uri.Path("/resource", Uri.Path.Type.Regular)
      )

    val uri2 =
      Uri(
        scheme = "http",
        authority = Some(Uri.Authority(server.hostString, server.port)),
        path = Uri.Path("/resource-unknown", Uri.Path.Type.Regular)
      )

    val request1 =
      ClientRequest(
        uri = uri1.toString,
        method = Method.get
      )

    val request2 =
      ClientRequest(
        uri = uri1.toString,
        method = Method.post
      )

    val request3 =
      ClientRequest(
        uri = uri2.toString,
        method = Method.get
      )

    val response1 =
      httpService.call(request1)

    val response2 =
      httpService.call(request2)

    val response3 =
      httpService.call(request3)

    val result1 = Await.result(for {
      x <- response1
      y <- x.handleEntity(EntityRequirement.None)
    } yield (x, y), 4 seconds)

    val result2 = Await.result(for {
      x <- response2
      y <- x.handleEntity(EntityRequirement.Json).mapTo[JsonEntity]
    } yield (x, y), 4 seconds)

    val result3 = Await.result(for {
      x <- response3
      y <- x.handleEntity(EntityRequirement.Json).mapTo[JsonEntity]
    } yield (x, y), 4 seconds)

    result1._1.statusCode shouldBe 200
    result2._1.statusCode shouldBe 404
    result3._1.statusCode shouldBe 404

    route.unregister()
  }
}
