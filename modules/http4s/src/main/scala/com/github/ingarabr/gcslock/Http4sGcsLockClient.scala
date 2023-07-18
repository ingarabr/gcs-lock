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

  private val fields = List(
    "id",
    "generation",
    "metadata/*"
  )

  private def lockBody(lockOwnerIdentifier: LockOwner[F]): Part[F] =
    lockOwnerIdentifier match {
      case _: LockOwner.Empty[_] =>
        Part[F](Headers.empty, fs2.Stream.empty)
      case LockOwner.Resolve(run) =>
        Part[F](
          Headers(`Content-Type`(MediaType.text.plain)),
          fs2.Stream.eval(run).through(fs2.text.utf8.encode)
        )
    }

  override def acquireLock(
      lockId: LockId,
      ttl: FiniteDuration,
      lockOwner: LockOwner[F]
  ): F[Option[LockMeta]] =
    createMetadata(ttl)
      .flatMap(createMultipartBody(_, lockBody(lockOwner)))
      .flatMap(body =>
        c.run(
          Request(
            method = Method.POST,
            uri = uploadUri(lockId)
              .withQueryParam("name", lockId.objectName)
              .withQueryParam("uploadType", "multipart")
              .withQueryParam("fields", fields.mkString(","))
              .withIfGenerationMatch(0),
            headers = Headers(credentials.authHeader, multipartHeader(body))
          ).withEntity(body)
        ).use(response => {
          response.status match {
            case Status.PreconditionFailed => None.pure[F]
            case Status.Ok => parseResponse(lockId, response).flatMap(_.liftTo[F].map(_.some))
            case _         => createError(response).flatMap(_.raiseError)
          }
        })
      )

  override def getLock(lockId: LockId): F[Option[LockMeta]] =
    c.run(
      Request(
        method = Method.GET,
        uri = basePath(lockId).withQueryParam("fields", fields.mkString(",")),
        headers = Headers(credentials.authHeader)
      )
    ).use(res =>
      res.status match {
        case Status.NotFound => None.pure[F]
        case Status.Ok       => parseResponse(lockId, res).flatMap(_.liftTo[F].map(_.some))
        case _               => createError(res).flatMap(_.raiseError)
      }
    )

  override def refreshLock(lock: LockMeta, ttl: FiniteDuration): F[RefreshStatus] =
    createMetadata(ttl)
      .flatMap(thePatch =>
        c.run(
          Request(
            method = Method.PATCH,
            uri = basePath(lock.id)
              .withIfGenerationMatch(lock)
              .withQueryParam("fields", fields.mkString(",")),
            headers = Headers(credentials.authHeader)
          ).withEntity(thePatch)
        ).use(response =>
          response.status match {
            case Status.Ok =>
              parseResponse(lock.id, response).flatMap {
                case Left(value)  => RefreshStatus.Error(value).pure[F]
                case Right(value) => RefreshStatus.Refreshed(value).pure[F]
              }
            case Status.NotFound =>
              RefreshStatus.LockMismatch(lock).pure[F]
            case Status.PreconditionFailed =>
              RefreshStatus.LockMismatch(lock).pure[F]
            case _ =>
              createError(response).map(value => RefreshStatus.Error(value))
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
        case _                         => createError(response).flatMap(_.raiseError)
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
        case _                => createError(response).flatMap(_.raiseError)
      }
    )

  private def uploadUri(lockId: LockId) =
    uri"https://storage.googleapis.com/upload/storage/v1/b/" / lockId.bucket / "o"

  private def basePath(lockId: LockId) =
    uri"https://storage.googleapis.com/storage/v1/b" / lockId.bucket / "o" / lockId.objectName

  private def createError[A](res: Response[F]): F[Throwable] =
    res.bodyText.compile.string.map(body =>
      new IllegalStateException(show"Unexpected http code: ${res.status} body:\n$body")
    )

  private def multipartHeader(body: Multipart[F]) =
    Header.Raw(CIString("Content-Type"), s"multipart/related; boundary=${body.boundary.value}")

  private def createMetadata(ttl: FiniteDuration): F[Json] =
    offsetDateTimeUtc
      .map(_.plusSeconds(ttl.toSeconds))
      .map(validTo => Json.obj("metadata" -> Json.obj("ttl" -> Json.fromString(validTo.toString))))

  private def createMultipartBody(
      metadata: Json,
      bodyContent: Part[F]
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

  private def parseResponse(lockId: LockId, res: Response[F]): F[Either[Throwable, LockMeta]] =
    jsonDecoder
      .decode(res, false)
      .value
      .rethrow
      .map { json =>
        val cur = json.hcursor
        val lock = for {
          ttl <- cur.downField("metadata").downField("ttl").as[OffsetDateTime]
          gen <- cur.downField("generation").as[Long]
        } yield LockMeta(lockId, ttl, gen)
        lock.leftMap(err => new IllegalStateException(s"Input json: ${json.spaces2}", err))
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
