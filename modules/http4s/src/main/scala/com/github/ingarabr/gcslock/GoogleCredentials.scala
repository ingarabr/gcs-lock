package com.github.ingarabr.gcslock

trait GoogleCredentials {
  def token: String // todo Should this be in context of F?
}
