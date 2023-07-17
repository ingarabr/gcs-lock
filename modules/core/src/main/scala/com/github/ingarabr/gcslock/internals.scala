package com.github.ingarabr.gcslock

import cats.effect.*
import cats.syntax.all.*

import java.time.{Instant, OffsetDateTime, ZoneOffset}
import scala.concurrent.duration.FiniteDuration

private[gcslock] def offsetDateTimeUtc[F[_]: Clock] =
  Clock[F].applicative.map(Clock[F].realTime)(d =>
    OffsetDateTime.from(Instant.ofEpochMilli(d.toMillis).atZone(ZoneOffset.UTC))
  )

private[gcslock] def runRetry[F[_]: Async: Concurrent, E <: Throwable, A](
    fa: F[Either[E, A]],
    delay: FiniteDuration,
    retryLeft: Int
): F[A] =
  fa.flatMap {
    case Right(value) => value.pure[F]
    case Left(err) =>
      if (retryLeft - 1 == 0)
        new IllegalStateException("Reached max number of attempts", err).raiseError[F, A]
      else Async[F].delayBy(runRetry(fa, delay, retryLeft - 1), delay)
  }
