package systems.opalia.service.http.impl.akka

import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.HttpHeader.ParsingResult
import akka.http.scaladsl.model.MediaType.{WithFixedCharset, WithOpenCharset}
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{ModeledCustomHeader, ModeledCustomHeaderCompanion}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.StreamConverters
import akka.util.ByteString
import play.api.libs.json._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import systems.opalia.commons.codec.Hex
import systems.opalia.commons.crypto.Digest
import systems.opalia.commons.identifier.ObjectId
import systems.opalia.commons.json.JsonAstTransformer
import systems.opalia.commons.net.{Uri => CommonsUri}
import systems.opalia.interfaces.json.JsonAst
import systems.opalia.interfaces.rendering.Renderer
import systems.opalia.interfaces.vfs.{Checksum, DataCorruptionException, FileSystem}
import systems.opalia.service.http.api._
import systems.opalia.service.http.api.errors._


trait HttpHandling {

  final case class `X-Content-Checksum`(checksum: Checksum)
    extends ModeledCustomHeader[`X-Content-Checksum`] {

    override def renderInRequests = true

    override def renderInResponses = true

    override val companion = `X-Content-Checksum`

    override def value: String = s"${checksum.algorithm.toUpperCase};${Hex.encode(checksum.value)}"
  }

  object `X-Content-Checksum`
    extends ModeledCustomHeaderCompanion[`X-Content-Checksum`] {

    override val name = "X-Content-Checksum"

    override def parse(value: String): Try[`X-Content-Checksum`] = {

      Try {

        val separator = value.indexOf(";")

        if (separator == -1)
          throw new IllegalArgumentException("Cannot find separator in header value to parse checksum.")

        val algorithmName = value.slice(0, separator).trim.toUpperCase
        val code = Hex.decode(value.slice(separator + 1, value.length).trim)

        val algorithm =
          Digest.Algorithm.withNameOpt(algorithmName)
            .getOrElse(throw new IllegalArgumentException("Invalid algorithm name for checksum."))

        if (code.isEmpty)
          throw new IllegalArgumentException("Missing checksum in header value.")

        `X-Content-Checksum`(Checksum(algorithm.toString, code))
      }
    }
  }

  private def selectDigest(algorithmName: Option[String]): Digest = {

    algorithmName
      .map {
        algorithmName =>

          Digest.Algorithm.withNameOpt(algorithmName).map(Digest(_))
            .getOrElse(throw new IllegalArgumentException("Cannot handle unsupported checksum algorithm."))
      }
      .getOrElse {

        Digest(Digest.Algorithm.SHA256)
      }
  }

  protected def convertFromApiMethod(value: Method): HttpMethod = {

    HttpMethods.getForKey(value.toString)
      .getOrElse(throw new IllegalArgumentException("Cannot process unsupported HTTP method."))
  }

  protected def convertFromApiSimpleEntity(assetFs: FileSystem,
                                           entity: SimpleEntity,
                                           jsonTransformation: (JsonAst.JsonValue) => JsonAst.JsonValue)
                                          (implicit executionContext: ExecutionContext,
                                           materializer: ActorMaterializer): (UniversalEntity, Checksum) = {

    entity match {

      case x: JsonEntity => {

        val digest = selectDigest(None)
        val body = ByteString(Json.stringify(JsonAstTransformer.toPlayJson(jsonTransformation(x.body))))
        val checksum = Checksum(digest.algorithm.toString, digest.sign(body))

        val httpEntity =
          HttpEntity(
            contentType = ContentTypes.`application/json`,
            data = body
          )

        (httpEntity, checksum)
      }

      case x: CharacterEntity => {

        val contentType = convertFromApiContentType(x.contentType)

        if (!isCharacterEntity(contentType))
          throw new UnsupportedMediaTypeHttpException(ServerErrorData(messages = List(
            "Require character based body to proceed."
          )))

        val charset = contentType.charsetOption.map(_.value).getOrElse(Renderer.appDefaultCharset.toString)
        val digest = selectDigest(None)
        val body = ByteString(x.body, charset)
        val checksum = Checksum(digest.algorithm.toString, digest.sign(body))

        val httpEntity =
          HttpEntity(
            contentType = contentType,
            data = body
          )

        (httpEntity, checksum)
      }

      case x: BinaryEntity => {

        val contentType = convertFromApiContentType(x.contentType)
        val digest = selectDigest(None)
        val body = ByteString(x.body.toArray)
        val checksum = Checksum(digest.algorithm.toString, digest.sign(body))

        val httpEntity =
          HttpEntity(
            contentType = contentType,
            data = body
          )

        (httpEntity, checksum)
      }

      case x: AssetEntity => {

        val fileContent = assetFs.fetchFileContent(x.id)
        val contentType = convertFromApiContentType(fileContent.contentType)

        val httpEntity =
          HttpEntity(
            contentType = contentType,
            contentLength = fileContent.fileSize,
            data = StreamConverters.fromInputStream(() =>
              fileContent.read(checkIntegrity = true))
          )

        (httpEntity, fileContent.checksum)
      }
    }
  }

  protected def convertFromApiEntity(assetFs: FileSystem,
                                     cacheFs: FileSystem,
                                     entity: Entity,
                                     jsonTransformation: (JsonAst.JsonValue) => JsonAst.JsonValue)
                                    (implicit executionContext: ExecutionContext,
                                     materializer: ActorMaterializer): Future[(MessageEntity, Option[Checksum])] = {

    entity match {

      case NoneEntity =>
        Future.successful(HttpEntity.Empty, None)

      case x: SimpleEntity => {

        val (httpEntity, checksum) = convertFromApiSimpleEntity(assetFs, x, jsonTransformation)

        Future.successful((httpEntity, Some(checksum)))
      }

      case x: MultipartEntity => {

        val parts =
          x.body.toSeq
            .map {
              case (name, part) =>

                val (httpEntity, checksum) = convertFromApiSimpleEntity(assetFs, part, jsonTransformation)

                val fileName =
                  part match {
                    case x: AssetEntity =>
                      Some(assetFs.fetchFileContent(x.id).fileName)
                    case _ =>
                      None
                  }

                val body =
                  Multipart.FormData.BodyPart(name, httpEntity, Map.empty ++ fileName.map(x => "filename" -> x))

                (body, checksum)
            }

        Marshal(Multipart.FormData(parts.map(_._1): _*)).to[MessageEntity]
          .flatMap {
            httpEntity =>

              val id = new Array[Byte](ObjectId.length)

              val outputStream = cacheFs.create(id, fileName = "", convertToApiContentType(httpEntity.contentType))

              httpEntity.dataBytes
                .runForeach {
                  data =>

                    outputStream.write(data.toArray)
                    outputStream.flush()
                }
                .andThen {
                  case _ =>

                    outputStream.close()
                }
                .map {
                  _ =>

                    val fileContent = cacheFs.fetchFileContent(id)

                    val httpEntity =
                      HttpEntity(
                        contentType = convertFromApiContentType(fileContent.contentType),
                        contentLength = fileContent.fileSize,
                        data = StreamConverters.fromInputStream(() =>
                          fileContent.read(checkIntegrity = false))
                      )

                    (httpEntity, Some(fileContent.checksum))
                }
          }
      }
    }
  }

  protected def convertToApiEntity(assetFs: FileSystem,
                                   cacheFs: FileSystem,
                                   message: HttpMessage,
                                   uri: String,
                                   entityRequirement: EntityRequirement)
                                  (implicit executionContext: ExecutionContext,
                                   materializer: ActorMaterializer): Future[Entity] = {

    val checksum = message.header[`X-Content-Checksum`].map(_.checksum)

    entityRequirement match {

      case EntityRequirement.None => {

        message.discardEntityBytes()

        Future.successful(NoneEntity)
      }

      case EntityRequirement.Json => {

        parseJsonBody(message.entity, checksum)
      }

      case EntityRequirement.Characters => {

        parseCharacterBody(message.entity, checksum)
      }

      case EntityRequirement.Binary => {

        parseBinaryBody(message.entity, checksum)
      }

      case EntityRequirement.Asset => {

        val fileName =
          message.headers
            .find(_.name() == headers.`Content-Disposition`.name)
            .flatMap(x => headers.`Content-Disposition`.parseFromValueString(x.value()) match {
              case Right(result) => result.params.get("filename")
              case _ => None
            })
            .getOrElse(CommonsUri(uri).path.lastOption.getOrElse(""))

        parseAssetBody(assetFs, fileName, message.entity, checksum)
      }

      case EntityRequirement.Multipart => {

        parseMultipartBody(assetFs, cacheFs, message.entity, checksum)
      }
    }
  }

  protected def convertFromApiContentType(contentType: String): ContentType = {

    if (contentType.isEmpty)
      ContentTypes.NoContentType
    else {
      ContentType.parse(contentType) match {
        case Right(result) =>
          result
        case Left(error) =>
          throw new IllegalArgumentException(
            s"Cannot parse content type; ${error.map(_.formatPretty).mkString(", ")}")
      }
    }
  }

  protected def convertToApiContentType(contentType: ContentType): String = {

    if (contentType == ContentTypes.NoContentType)
      ""
    else
      contentType.toString
  }

  protected def convertFromApiHeaders(customHeaders: Seq[(String, String)],
                                      entityTag: Option[EntityTag],
                                      checksum: Option[Checksum]): List[HttpHeader] = {

    def parse(headers: Seq[(String, String)]): List[HttpHeader] =
      headers.toList
        .map(x => HttpHeader.parse(x._1, x._2))
        .map {
          case result: ParsingResult.Ok =>
            result.header
          case result: ParsingResult.Error =>
            throw new IllegalArgumentException(
              s"Cannot parse header; ${result.errors.map(_.formatPretty).mkString(", ")}")
        }

    val httpHeaders =
      List(headers.`Date`(DateTime.now)) ++
        entityTag.map(x => headers.`ETag`(x.tag, x.weak)) ++
        checksum.map(x => `X-Content-Checksum`(x)) ++
        parse(customHeaders)

    if (httpHeaders.size != httpHeaders.map(_.name()).distinct.size)
      throw new IllegalArgumentException("Expect distinct header names.")

    httpHeaders
  }

  protected def convertToApiHeadersServer(httpHeaders: Seq[HttpHeader]): Map[String, String] = {

    val filteredHeaders = httpHeaders.filter(_.isNot("timeout-access"))

    val result = filteredHeaders.map(x => (x.name(), x.value())).toMap

    if (filteredHeaders.size != result.size)
      throw new BadRequestHttpException(ServerErrorData(messages = List(
        "Cannot handle invalid header.",
        "Expect distinct header names."
      )))

    result
  }

  protected def convertToApiHeadersClient(httpHeaders: Seq[HttpHeader]): Seq[(String, String)] = {

    val result = httpHeaders.map(x => (x.name(), x.value()))

    result
  }

  protected def isJsonEntity(contentType: ContentType): Boolean =
    contentType == MediaTypes.`application/json`.toContentType

  protected def isCharacterEntity(contentType: ContentType): Boolean =
    contentType.mediaType.isInstanceOf[WithOpenCharset] || contentType.mediaType.isInstanceOf[WithFixedCharset]

  protected def isMultipartEntity(contentType: ContentType): Boolean =
    contentType.mediaType.withParams(Map.empty) == MediaTypes.`multipart/form-data`

  protected def parseJsonBody(entity: HttpEntity,
                              checksum: Option[Checksum])
                             (implicit executionContext: ExecutionContext,
                              materializer: ActorMaterializer): Future[JsonEntity] = {

    if (!isJsonEntity(entity.contentType))
      throw new UnsupportedMediaTypeHttpException(ServerErrorData(messages = List(
        "Require JSON body to proceed."
      )))

    parseCharacterBody(entity, checksum)
      .map {
        body =>

          Try(JsonAstTransformer.fromPlayJson(Json.parse(body.body))) match {
            case Failure(e) =>
              throw new BadRequestHttpException(ServerErrorData(messages = List(
                "Cannot parse malformed JSON body.",
                e.getMessage
              )))
            case Success(x: JsonAst.JsonObject) =>
              JsonEntity(x)
            case _ =>
              throw new BadRequestHttpException(ServerErrorData(messages = List(
                "Cannot parse malformed JSON body.",
                "Require JSON object to proceed."
              )))
          }
      }
  }

  protected def parseCharacterBody(entity: HttpEntity,
                                   checksum: Option[Checksum])
                                  (implicit executionContext: ExecutionContext,
                                   materializer: ActorMaterializer): Future[CharacterEntity] = {

    val digest = selectDigest(checksum.map(_.algorithm))

    if (!isCharacterEntity(entity.contentType))
      throw new UnsupportedMediaTypeHttpException(ServerErrorData(messages = List(
        "Require character based body to proceed."
      )))

    entity.dataBytes
      .runFold(ByteString())(_ ++ _)
      .map {
        data =>

          if (checksum.exists(x => digest.sign(data) != x.value))
            throw new BadRequestHttpException(ServerErrorData(messages = List(
              "Cannot validate checksum for corrupted character based body.",
            )))

          CharacterEntity(
            convertToApiContentType(entity.contentType),
            entity.contentType
              .charsetOption.map(x => data.decodeString(x.value))
              .getOrElse(data.decodeString(Renderer.appDefaultCharset))
          )
      }
  }

  protected def parseBinaryBody(entity: HttpEntity,
                                checksum: Option[Checksum])
                               (implicit executionContext: ExecutionContext,
                                materializer: ActorMaterializer): Future[BinaryEntity] = {

    val digest = selectDigest(checksum.map(_.algorithm))

    entity.dataBytes
      .runFold(ByteString())(_ ++ _)
      .map {
        data =>

          if (checksum.exists(x => digest.sign(data) != x.value))
            throw new BadRequestHttpException(ServerErrorData(messages = List(
              "Cannot validate checksum for corrupted body.",
            )))

          BinaryEntity(
            convertToApiContentType(entity.contentType),
            data.toVector
          )
      }
  }

  protected def parseAssetBody(assetFs: FileSystem,
                               fileName: String,
                               entity: HttpEntity,
                               checksum: Option[Checksum])
                              (implicit executionContext: ExecutionContext,
                               materializer: ActorMaterializer): Future[AssetEntity] = {

    createAssetEntity(assetFs, fileName, entity, checksum)
  }

  protected def parseMultipartBody(assetFs: FileSystem,
                                   cacheFs: FileSystem,
                                   entity: HttpEntity,
                                   checksum: Option[Checksum])
                                  (implicit executionContext: ExecutionContext,
                                   materializer: ActorMaterializer): Future[MultipartEntity] = {

    if (!isMultipartEntity(entity.contentType))
      throw new UnsupportedMediaTypeHttpException(ServerErrorData(messages = List(
        "Require multipart/form-data body to proceed."
      )))

    val id = new Array[Byte](ObjectId.length)

    val outputStream = cacheFs.create(id, fileName = "", convertToApiContentType(entity.contentType), checksum)

    entity.dataBytes
      .runForeach {
        data =>

          outputStream.write(data.toArray)
          outputStream.flush()
      }
      .andThen {
        case _ =>

          try {

            outputStream.close()

          } catch {

            case e: DataCorruptionException =>
              throw new BadRequestHttpException(ServerErrorData(messages = List(
                "Cannot validate checksum for corrupted multipart/form-data body.",
              )))
          }
      }
      .flatMap {
        _ =>

          val fileContent = cacheFs.fetchFileContent(id)

          val data =
            Unmarshal(HttpEntity(
              contentType = convertFromApiContentType(fileContent.contentType),
              contentLength = fileContent.fileSize,
              data = StreamConverters.fromInputStream(() =>
                fileContent.read(checkIntegrity = false))
            ))
              .to[Multipart.FormData]
              .flatMap {
                body =>

                  body.parts
                    .mapAsync(parallelism = 1) {
                      part =>

                        part.filename.map {
                          filename =>

                            createAssetEntity(assetFs, filename, part.entity, None)
                              .map(body => part.name -> body)

                        } getOrElse {

                          if (isJsonEntity(part.entity.contentType))
                            parseJsonBody(part.entity, None)
                              .map(body => part.name -> body)
                          else if (isCharacterEntity(part.entity.contentType))
                            parseCharacterBody(part.entity, None)
                              .map(body => part.name -> body)
                          else
                            parseBinaryBody(part.entity, None)
                              .map(body => part.name -> body)
                        }

                    }
                    .runFold(Seq.empty[(String, SimpleEntity)])(_ :+ _)
              }

          data.map {
            parts =>

              val map = parts.toMap

              if (parts.size != map.size)
                throw new BadRequestHttpException(ServerErrorData(messages = List(
                  "Cannot handle invalid multipart/form-data body.",
                  "Expect unique names of multipart/form-data parts."
                )))

              MultipartEntity(map)
          }
      }
  }

  protected def createAssetEntity(assetFs: FileSystem,
                                  fileName: String,
                                  entity: HttpEntity,
                                  checksum: Option[Checksum])
                                 (implicit executionContext: ExecutionContext,
                                  materializer: ActorMaterializer): Future[AssetEntity] = {

    val id = new Array[Byte](ObjectId.length)

    val sink =
      StreamConverters.fromOutputStream(
        () => assetFs.create(id, fileName, convertToApiContentType(entity.contentType), checksum),
        autoFlush = true
      )

    entity.dataBytes.runWith(sink)
      .map {
        _ =>

          try {

            assetFs.fetchFileContent(id).validate(failIfFalse = true)

          } catch {

            case e: DataCorruptionException =>
              throw new IllegalArgumentException(e)
          }

          AssetEntity(id)
      }
      .recover {
        case e: DataCorruptionException =>
          throw new BadRequestHttpException(ServerErrorData(messages = List(
            "Cannot validate checksum for corrupted asset body.",
            e.getMessage
          )))
      }
  }
}
