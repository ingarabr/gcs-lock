import Versions.V

inThisBuild(
  Seq(
    organization := "com.github.ingarabr",
    version := "0.0.1-SNAPSHOT",
    scalacOptions += "-no-indent",
    scalaVersion := "3.2.2",
    testFrameworks += new TestFramework("munit.Framework")
  )
)

lazy val core = (project in file("modules/core"))
  .settings(
    name := "gcs-lock-core",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % V.CatsEffect,
      "org.typelevel" %% "munit-cats-effect-3" % V.MunitCatsEffect % Test,
      "org.scalameta" %% "munit" % V.Munit % Test
    )
  )

lazy val http4s = (project in file("modules/http4s"))
  .dependsOn(core)
  .settings(
    name := "gcs-lock-http4s",
    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-ember-client" % V.Http4s,
      "org.http4s" %% "http4s-circe" % V.Http4s,
      "org.http4s" %% "http4s-dsl" % V.Http4s,
      "org.typelevel" %% "cats-effect" % V.CatsEffect,
      "org.typelevel" %% "munit-cats-effect-3" % V.MunitCatsEffect % Test,
      "org.scalameta" %% "munit" % V.Munit % Test
    )
  )
