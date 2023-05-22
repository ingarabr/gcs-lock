package com.github.ingarabr.gcslock

import cats.syntax.all.*
import cats.effect.*
import cats.*

import scala.concurrent.TimeoutException
import scala.concurrent.duration.*

case class Strategy[F[_]](
    getLock: F[Option[LockMeta]] => F[LockMeta],
    refreshInterval: FiniteDuration,
    timeToLive: FiniteDuration
)

object Strategy {
  val defaultLockRefreshInterval: FiniteDuration = 37.seconds
  val defaultLockTimeToLive: FiniteDuration = 5.minutes

  private def runRetryF[F[_]: Async: Concurrent](
      fa: F[Option[LockMeta]],
      delay: FiniteDuration,
      retryLeft: Int
  ): F[LockMeta] =
    fa.flatMap {
      case Some(value) => value.pure[F]
      case None =>
        if (retryLeft - 1 == 0)
          new TimeoutException("Reached max number of attempts").raiseError[F, LockMeta]
        else Async[F].delayBy(runRetryF(fa, delay, retryLeft - 1), delay)
    }

  def waitUntilLockIsAvailable[F[_]: Async: Concurrent](
      acquireRetryMaxAttempts: Int = 100,
      acquireRetryInterval: FiniteDuration = 1.minute,
      lockRefreshInterval: FiniteDuration = defaultLockRefreshInterval,
      lockTimeToLive: FiniteDuration = defaultLockTimeToLive
  ): Strategy[F] =
    Strategy[F](
      fa => runRetryF(fa, acquireRetryInterval, acquireRetryMaxAttempts),
      lockRefreshInterval,
      lockTimeToLive
    )

  def acquireOnlyIfAvailable[F[_]: Async](
      lockRefreshInterval: FiniteDuration = defaultLockRefreshInterval,
      lockTimeToLive: FiniteDuration = defaultLockTimeToLive
  ): Strategy[F] =
    Strategy[F](
      { _.flatMap(_.liftTo(new IllegalStateException("Lock already taken"))) },
      lockRefreshInterval,
      lockTimeToLive
    )

}
