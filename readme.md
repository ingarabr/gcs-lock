# GCP Lock

## Motivation

Cloud infrastructure are designed to be robust and have high availability. Those properties
is something we want for a distributed locking mechanism.  


## Inspiration

Based on the blog post [A robust distributed locking algorithm based on Google Cloud Storage](https://www.joyfulbikeshedding.com/blog/2021-05-19-robust-distributed-locking-algorithm-based-on-google-cloud-storage.html)


## How to use it

Dependencies
```sbt
// http4s implementation
libraryDependencies += "com.github.ingarabr" % "gcs-lock-http4s" %% version 
// optional: using cats-retry
libraryDependencies += "com.github.ingarabr" % "gcs-lock-cats-retry" %% version 
```

Simplified example
```scala
import cats.effect.IO
import com.github.ingarabr.gcslock._

// http4s client
val http: org.http4s.client.Client[IO] = ???
// GCP token. For instance from: `gcloud auth print-access-token`
val cred: GoogleCredentials = ??? 
val lockClient = new Http4sGcsLockClient[IO](http, cred)

GcsLock[IO](lockClient)
  .create(
    // The lock-id defined where the lock is located. Require write access to the bucket location.
    LockId("my-bucket-name","gcs/path/to-lock-identifier"),
    // Human readable string that identify the owner of the lock.
    LockOwner.fromHostName[IO], 
    // Different strategies on how we want to acquire and refresh the lock.
    // Use the NoDependencyStrategy if you do not want to depend on cats-retry.
    CatsRetryStrategy.waitUntilLockIsAvailable[IO]()
  ).surround {
    IO.unit // my app
  }
```