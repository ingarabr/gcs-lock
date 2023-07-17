package com.github.ingarabr.gcslock

import cats.{Applicative, Functor, MonadThrow}
import cats.effect.implicits.{genSpawnOps, genTemporalOps_}
import cats.effect.kernel.Concurrent
import cats.syntax.all.*
import cats.effect.{Async, Ref, Resource}
import com.github.ingarabr.gcslock.GcsLock.LockAcquireError

class GcsLock[F[_]: Async](client: GcsLockClient[F]) {

  def create(id: LockId, strategy: Strategy[F]): Resource[F, Unit] = {

    def refreshLock(lock: Ref[F, LockMeta]): F[Unit] =
      Async[F]
        .sleep(strategy.refreshInterval)
        .flatMap(_ =>
          Async[F].uncancelable(_ =>
            for {
              oldLock <- lock.get
              status <- strategy.attemptRefresh {
                client.refreshLock(oldLock, strategy.timeToLive).attempt
              }
              _ <-
                status match {
                  case RefreshStatus.Refreshed(newLockMeta) => lock.set(newLockMeta)
                  case RefreshStatus.LockMismatch(oldLockMeta) =>
                    client.getLock(oldLockMeta.id).flatMap {
                      case Some(_) =>
                        LockAcquireError(s"Refresh failed. Lock mismatch").raiseError
                      case None =>
                        LockAcquireError("Refresh failed. Lock not found!").raiseError
                    }

                  case RefreshStatus.Error(err) =>
                    LockAcquireError("Refresh failed.", err).raiseError
                }
            } yield ()
          )
        )

    val runAcquire =
      client.acquireLock(id, strategy.timeToLive).flatMap {
        case Some(value) => value.some.pure[F]
        case None =>
          client
            .getLock(id)
            .flatMap {
              case Some(value) =>
                value.validTTL.flatMap {
                  case true  => none.pure[F]
                  case false => client.acquireLock(id, strategy.timeToLive)
                }

              case None => client.acquireLock(id, strategy.timeToLive)
            }
      }

    val acquire = for {
      lock <- strategy.attemptAcquire(runAcquire)
      ref <- Ref.of[F, LockMeta](lock)
      fiber <- refreshLock(ref).start
    } yield fiber.cancel.flatMap(_ => ref.get)

    Resource
      .make(acquire)(_.flatMap(lock => client.releaseLock(lock).void))
      .void
  }

}

object GcsLock {
  def apply[F[_]: Async](client: GcsLockClient[F]): GcsLock[F] =
    new GcsLock[F](client)

  class LockAcquireError(msg: String, underlying: Option[Throwable])
      extends RuntimeException(msg, underlying.orNull)

  object LockAcquireError {
    def apply(msg: String) = new LockAcquireError(msg, None)
    def apply(msg: String, err: Throwable) = new LockAcquireError(msg, Some(err))
  }
}
