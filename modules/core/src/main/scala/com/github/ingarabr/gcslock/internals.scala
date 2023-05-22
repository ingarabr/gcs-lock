package com.github.ingarabr.gcslock

import cats.effect.*

import java.time.{Instant, OffsetDateTime, ZoneOffset}

private[gcslock] def offsetDateTimeUtc[F[_]: Clock] =
  Clock[F].applicative.map(Clock[F].realTime)(d =>
    OffsetDateTime.from(Instant.ofEpochMilli(d.toMillis).atZone(ZoneOffset.UTC))
  )
