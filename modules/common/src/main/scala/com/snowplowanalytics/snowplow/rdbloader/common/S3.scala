/*
 * Copyright (c) 2012-2021 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.rdbloader.common

import cats.syntax.either._

import io.circe.{Encoder, Json, Decoder}

import shapeless.tag
import shapeless.tag._

/**
 * Common types and functions for Snowplow S3 clients
 */
object S3 {

  /**
   * Refined type for AWS S3 bucket, allowing only valid S3 paths
   * (with `s3://` prefix and trailing shash)
   */
  type Folder = String @@ S3FolderTag

  implicit class FolderOps(f: Folder) {
    def withKey(s: String): S3.Key =
      S3.Key.join(f, s)

    def append(s: String): S3.Folder =
      Folder.append(f, s)

    def folderName: String =
      f.split("/").last

    def bucketName: String =
      f.split("://").last.split("/").head

    /**
     * Find diff of two S3 paths.
     * Return None if parent path is longer than sub path or
     * parent path doesn't match with sub path.
     */
    def diff(parent: Folder): Option[String] = {
      def go(parentParts: List[String], subParts: List[String]): Option[String] =
        (parentParts, subParts) match {
          case (_, Nil) =>
            None
          case (Nil, s) =>
            Some(s.mkString("/"))
          case (pHead :: _, sHead :: _) if pHead != sHead =>
            None
          case (pHead :: pTail, sHead :: sTail) if pHead == sHead =>
            go(pTail, sTail)
        }

      go(parent.split("/").toList, f.split("/").toList)
    }
  }


  object Folder extends tag.Tagger[S3FolderTag] {

    def parse(s: String): Either[String, Folder] = s match {
      case _ if !correctlyPrefixed(s) => s"Bucket name $s doesn't start with s3:// s3a:// or s3n:// prefix".asLeft
      case _ if s.length > 1024       => "Key length cannot be more than 1024 symbols".asLeft
      case _                          => coerce(s).asRight
    }

    /** Turn proper `s3://bucket/path/` string into `Folder` */
    def coerce(s: String): Folder =
      apply(appendTrailingSlash(fixPrefix(s)).asInstanceOf[Folder])

    def append(s3Bucket: Folder, s: String): Folder = {
      val normalized = if (s.endsWith("/")) s else s + "/"
      coerce(s3Bucket + normalized)
    }

    def getParent(key: Folder): Folder = {
      val string = key.split("/").dropRight(1).mkString("/")
      coerce(string)
    }

    private def appendTrailingSlash(s: String): String =
      if (s.endsWith("/")) s
      else s + "/"

  }

  /**
   * Extract `s3://path/run=YYYY-MM-dd-HH-mm-ss/atomic-events` part from
   * Set of prefixes that can be used in config.yml
   * In the end it won't affect how S3 is accessed
   */
  val supportedPrefixes = Set("s3", "s3n", "s3a")

  private def correctlyPrefixed(s: String): Boolean =
    supportedPrefixes.foldLeft(false) { (result, prefix) =>
      result || s.startsWith(s"$prefix://")
    }

  case class BlobObject(key: Key, size: Long)

  /**
   * Refined type for AWS S3 key,  allowing only valid S3 paths
   * (with `s3://` prefix and without trailing shash)
   */
  type Key = String @@ S3KeyTag

  object Key extends tag.Tagger[S3KeyTag] {

    def join(folder: Folder, name: String): Key =
      coerce(folder + name)

    def getParent(key: Key): Folder = {
      val string = key.split("/").dropRight(1).mkString("/")
      Folder.coerce(string)
    }

    def coerce(s: String): Key =
      fixPrefix(s).asInstanceOf[Key]

    def parse(s: String): Either[String, Key] = s match {
      case _ if !correctlyPrefixed(s) => "S3 key must start with s3:// prefix".asLeft
      case _ if s.length > 1024       => "Key length cannot be more than 1024 symbols".asLeft
      case _ if s.endsWith("/")       => "S3 key cannot have trailing slash".asLeft
      case _                          => coerce(s).asRight
    }
  }

  implicit class KeyOps(k: Key) {
    def getParent: S3.Folder =
      S3.Key.getParent(k)
  }

  // Tags for refined types
  sealed trait S3FolderTag
  sealed trait S3KeyTag
  sealed trait AtomicEventsKeyTag

  implicit val s3FolderDecoder: Decoder[Folder] =
    Decoder.decodeString.emap(Folder.parse)
  implicit val s3FolderEncoder: Encoder[Folder] =
    Encoder.instance(Json.fromString)

  /**
   * Split S3 path into bucket name and folder path
   *
   * @param path S3 full path with `s3://` and with trailing slash
   * @return pair of bucket name and remaining path ("some-bucket", "some/prefix/")
   */
  private[rdbloader] def splitS3Path(path: Folder): (String, String) =
    path.stripPrefix("s3://").split("/").toList match {
      case head :: Nil => (head, "/")
      case head :: tail => (head, tail.mkString("/") + "/")
      case Nil => throw new IllegalArgumentException(s"Invalid S3 bucket path was passed")  // Impossible
    }

  /**
   * Split S3 key into bucket name and filePath
   *
   * @param key S3 full path with `s3://` prefix and without trailing slash
   * @return pair of bucket name and remaining path ("some-bucket", "some/prefix/")
   */
  def splitS3Key(key: Key): (String, String) =
    key.stripPrefix("s3://").split("/").toList match {
      case head :: tail => (head, tail.mkString("/").stripSuffix("/"))
      case _ => throw new IllegalArgumentException(s"Invalid S3 key [$key] was passed")  // Impossible
    }

  /** Used only to list S3 directories, not to read and write data. */
  private def fixPrefix(s: String): String =
    if (s.startsWith("s3n")) "s3" + s.stripPrefix("s3n")
    else if (s.startsWith("s3a")) "s3" + s.stripPrefix("s3a")
    else s
}
