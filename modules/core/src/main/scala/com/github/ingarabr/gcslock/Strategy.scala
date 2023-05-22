package com.github.ingarabr.gcslock

import cats.syntax.all.*
import cats.effect.*
import cats.*

import scala.concurrent.duration.*

case class Strategy[F[_]](
    attemptAcquire: F[Option[LockMeta]] => F[LockMeta],
    refreshInterval: FiniteDuration,
    timeToLive: FiniteDuration
)

object Strategy {
  val defaultLockRefreshInterval: FiniteDuration = 37.seconds
  val defaultLockTimeToLive: FiniteDuration = 5.minutes

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
