package com.github.ingarabr.gcslock

import cats.effect.Clock

import java.time.OffsetDateTime

case class LockId(
    bucket: String,
    objectName: String
)

case class LockMeta(
    id: LockId,
    validTo: OffsetDateTime,
    generation: Long
) {

  def validTTL[F[_]: Clock]: F[Boolean] =
    Clock[F].applicative
      .map(offsetDateTimeUtc)(time => time.isEqual(validTo) || time.isBefore(validTo))

}
