package com.github.ingarabr.gcslock

import cats.{Applicative, Functor}
import cats.data.EitherT
import cats.effect.{Async, Clock}
import cats.effect.kernel.Sync
import cats.syntax.all.*

import java.net.InetAddress
import java.time.{OffsetDateTime, ZoneId, ZoneOffset}
import scala.concurrent.duration.{Duration, FiniteDuration}

trait GcsLockClient[F[_]] {

  /** Attempt to acquire a lock */
  def acquireLock(
      lockId: LockId,
      ttl: FiniteDuration,
      lockOwner: LockOwner[F]
  ): F[Option[LockMeta]]

  /** Fetch a lock */
  def getLock(lockId: LockId): F[Option[LockMeta]]

  /** Refresh the ttl on a lock */
  def refreshLock(lock: LockMeta, ttl: FiniteDuration): F[RefreshStatus]

  /** Release a lock */
  def releaseLock(lock: LockMeta): F[Boolean]

  /** Force removing a lock. */
  def clearLock(lock: LockId): F[Boolean]
}

enum RefreshStatus {

  /** Everything went fine */
  case Refreshed(newLockMeta: LockMeta)

  /** Unable to refresh the lock. We can not recover from this and we must assume we do now own it
    * any more. Can also occur when the lock doesn't exist
    */
  case LockMismatch(oldLockMeta: LockMeta)

  /** Other errors like network issues and so on. */
  case Error(err: Throwable)
}

/** Opt-in identifier that's stored in the GCS content. It should be a human readable format that
  * makes it easier to identify the lock owner.
  *
  * Some good example on lock owner identifiers:
  *   - The host name of the machine
  *   - A kubernetes pod name
  *   - The node and job name of a Github action run.
  */
sealed trait LockOwner[F[_]]
object LockOwner {
  class Empty[F[_]] extends LockOwner[F]
  case class Resolve[F[_]](run: F[String]) extends LockOwner[F]

  def empty[F[_]] = new Empty[F]

  def fromEnv[F[_]: Sync](name: String): LockOwner[F] =
    Resolve(
      Sync[F]
        .delay(sys.env.get(name))
        .flatMap(
          _.toRight(new IllegalStateException(show"Environment variable not found: $name"))
            .liftTo[F]
        )
    )

  def fromHostName[F[_]: Sync]: LockOwner[F] =
    Resolve(
      Sync[F]
        .delay(InetAddress.getLocalHost.getHostName)
        .adaptError { case err => new IllegalStateException(show"Failed to get host name", err) }
    )

}
