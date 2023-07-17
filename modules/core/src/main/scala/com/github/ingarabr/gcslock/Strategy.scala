package com.github.ingarabr.gcslock

import cats.syntax.all.*
import cats.effect.*
import cats.*

import scala.concurrent.duration.*

case class Strategy[F[_]](
    attemptAcquire: F[Option[LockMeta]] => F[LockMeta],
    attemptRefresh: F[Either[Throwable, RefreshStatus]] => F[RefreshStatus],
    refreshInterval: FiniteDuration,
    timeToLive: FiniteDuration
)

object Strategy {
  val defaultLockRefreshInterval: FiniteDuration = 37.seconds
  val defaultLockTimeToLive: FiniteDuration = 5.minutes
  val defaultAcquireRetryInterval: FiniteDuration = 1.minutes

  def waitUntilLockIsAvailable[F[_]: Async: Concurrent](
      acquireRetryMaxAttempts: Int = 100,
      acquireRetryInterval: FiniteDuration = defaultAcquireRetryInterval,
      lockRefreshInterval: FiniteDuration = defaultLockRefreshInterval,
      lockTimeToLive: FiniteDuration = defaultLockTimeToLive
  ): Strategy[F] =
    Strategy[F](
      fa =>
        runRetry(
          fa.map(_.toRight(new IllegalStateException("Lock not acquired"))),
          acquireRetryInterval,
          acquireRetryMaxAttempts
        ),
      fa => runRetry(fa, acquireRetryInterval, 2),
      lockRefreshInterval,
      lockTimeToLive
    )

  def acquireOnlyIfAvailable[F[_]: Async](
      acquireRetryInterval: FiniteDuration = defaultAcquireRetryInterval,
      lockRefreshInterval: FiniteDuration = defaultLockRefreshInterval,
      lockTimeToLive: FiniteDuration = defaultLockTimeToLive
  ): Strategy[F] =
    Strategy[F](
      { _.flatMap(_.liftTo(new IllegalStateException("Lock already acquired"))) },
      fa => runRetry(fa, acquireRetryInterval, 2),
      lockRefreshInterval,
      lockTimeToLive
    )

}
