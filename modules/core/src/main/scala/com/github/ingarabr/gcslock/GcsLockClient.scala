package com.github.ingarabr.gcslock

import cats.{Applicative, Functor}
import cats.data.EitherT
import cats.effect.{Async, Clock}
import cats.effect.kernel.Sync
import cats.syntax.all.*

import java.time.{OffsetDateTime, ZoneId, ZoneOffset}
import scala.concurrent.duration.{Duration, FiniteDuration}

trait GcsLockClient[F[_]] {

  /** Attempt to acquire a lock */
  def acquireLock(lockId: LockId, ttl: FiniteDuration): F[Option[LockMeta]]

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
