package com.github.ingarabr.gcslock

import cats.{Applicative, Functor}
import cats.data.EitherT
import cats.effect.{Async, Clock}
import cats.effect.kernel.Sync
import org.http4s.*
import cats.syntax.all.*
import io.circe.Decoder.Result
import io.circe.Json
import org.http4s.headers.{Authorization, `Content-Type`}
import org.http4s.syntax.*
import org.http4s.circe.{CirceInstances, jsonDecoder, jsonEncoder}
import org.http4s.client.Client
import org.http4s.implicits.*
import org.http4s.multipart.{Boundary, Multipart, Multiparts, Part}
import org.typelevel.ci.CIString

import java.time.{OffsetDateTime, ZoneId, ZoneOffset}
import scala.concurrent.duration.{Duration, FiniteDuration}

class Http4sGcsLockClient[F[_]: Async](c: Client[F], credentials: GoogleCredentials)
    extends GcsLockClient[F] {

  override def acquireLock(lockId: LockId, ttl: FiniteDuration): F[Option[LockMeta]] =
    createMetadata(ttl)
      .flatMap(createMultipartBody(_))
      .flatMap(body =>
        c.run(
          Request(
            method = Method.POST,
            uri = uploadUri(lockId)
              .withQueryParam("name", lockId.objectName)
              .withQueryParam("uploadType", "multipart")
              .withIfGenerationMatch(0),
            headers = Headers(credentials.authHeader, multipartHeader(body))
          ).withEntity(body)
        ).use(response => {
          response.status match {
            case Status.PreconditionFailed => None.pure[F]
            case Status.Ok                 => parseResponse(lockId, response).map(_.some)
            case _                         => createError(response)
          }
        })
      )

  override def getLock(lockId: LockId): F[Option[LockMeta]] =
    c.run(
      Request(
        method = Method.GET,
        uri = basePath(lockId),
        headers = Headers(credentials.authHeader)
      )
    ).use(res =>
      res.status match {
        case Status.NotFound => None.pure[F]
        case Status.Ok       => parseResponse(lockId, res).map(_.some)
        case _               => createError(res)
      }
    )

  override def refreshLock(lock: LockMeta, ttl: FiniteDuration): F[LockMeta] =
    createMetadata(ttl)
      .flatMap(thePatch =>
        c.run(
          Request(
            method = Method.PATCH,
            uri = basePath(lock.id).withIfGenerationMatch(lock),
            headers = Headers(credentials.authHeader)
          ).withEntity(thePatch)
        ).use(response =>
          response.status match {
            case Status.Ok => Async[F].delay(println(thePatch)) *> parseResponse(lock.id, response)
            case _         => createError(response)
          }
        )
      )

  override def releaseLock(lock: LockMeta): F[Boolean] =
    c.run(
      Request(
        method = Method.DELETE,
        uri = basePath(lock.id).withIfGenerationMatch(lock),
        headers = Headers(credentials.authHeader)
      )
    ).use(response =>
      response.status match {
        case Status.NoContent          => true.pure[F]
        case Status.NotFound           => false.pure[F]
        case Status.PreconditionFailed => false.pure[F]
        case _                         => createError(response)
      }
    )

  override def clearLock(lockId: LockId): F[Boolean] =
    c.run(
      Request(
        method = Method.DELETE,
        uri = basePath(lockId),
        headers = Headers(credentials.authHeader)
      )
    ).use(response =>
      response.status match {
        case Status.NoContent => true.pure[F]
        case Status.NotFound  => false.pure[F]
        case _                => createError(response)
      }
    )

  private def uploadUri(lockId: LockId) =
    uri"https://storage.googleapis.com/upload/storage/v1/b/" / lockId.bucket / "o"

  private def basePath(lockId: LockId) =
    uri"https://storage.googleapis.com/storage/v1/b" / lockId.bucket / "o" / lockId.objectName

  private def createError[A](res: Response[F]) =
    res.bodyText.compile.string.flatMap(body =>
      new IllegalStateException(show"Unexpected http code: ${res.status} body:\n$body")
        .raiseError[F, A]
    )

  private def multipartHeader(body: Multipart[F]) =
    Header.Raw(CIString("Content-Type"), s"multipart/related; boundary=${body.boundary.value}")

  private def createMetadata(ttl: FiniteDuration): F[Json] =
    offsetDateTimeUtc
      .map(_.plusSeconds(ttl.toSeconds))
      .map(validTo => Json.obj("metadata" -> Json.obj("ttl" -> Json.fromString(validTo.toString))))

  private def createMultipartBody(
      metadata: Json,
      bodyContent: Part[F] = Part[F](Headers.empty, fs2.Stream.empty)
  ): F[Multipart[F]] =
    Multiparts.forSync.flatMap(
      _.multipart(
        Vector(
          Part(
            Headers(`Content-Type`(MediaType.application.json, Charset.`UTF-8`)),
            jsonEncoder.toEntity(metadata).body
          ),
          bodyContent
        )
      )
    )

  // todo: query param: ?fields=id,name,metadata/key1
  private def parseResponse(lockId: LockId, res: Response[F]) =
    jsonDecoder
      .decode(res, false)
      .value
      .rethrow
      .flatMap { json =>
        val cur = json.hcursor
        val lock = for {
          ttl <- cur.downField("metadata").downField("ttl").as[OffsetDateTime]
          gen <- cur.downField("generation").as[Long]
        } yield LockMeta(lockId, ttl, gen)
        lock.liftTo[F].adaptErr { case err =>
          new IllegalStateException(s"Input json: ${json.spaces2}", err)
        }
      }

  extension (u: Uri) {
    private def withIfGenerationMatch(l: LockMeta): Uri =
      withIfGenerationMatch(l.generation)
    private def withIfGenerationMatch(matches: Long): Uri =
      u.withQueryParam("ifGenerationMatch", matches)
  }
  extension (cred: GoogleCredentials) {
    private def authHeader: Authorization =
      Authorization(Credentials.Token(AuthScheme.Bearer, cred.token))
  }
}
