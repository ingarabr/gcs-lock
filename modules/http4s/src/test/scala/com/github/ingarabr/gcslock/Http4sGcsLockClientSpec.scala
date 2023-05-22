package com.github.ingarabr.gcslock

import cats.effect.{IO, Resource}
import munit.FunSuite
import org.http4s.ember.client.EmberClientBuilder

class Http4sGcsLockClientSpec extends GcLockClientSpec(Http4sGcsLockClientSpec.cloudLock)

object Http4sGcsLockClientSpec {
  val credentials: IO[GoogleCredentials] = IO {
    val accessToken = sys.process.Process(Seq("gcloud", "auth", "print-access-token")).!!
    new GoogleCredentials {
      override def token: String = accessToken
    }
  }

  val cloudLock = for {
    cred <- Resource.eval(credentials)
    http <- EmberClientBuilder.default[IO].build
  } yield new Http4sGcsLockClient[IO](http, cred)

}
