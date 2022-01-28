/*
 * Copyright (c) 2012-2020 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.loader.redshift

import com.snowplowanalytics.iglu.core.{SchemaVer, SchemaKey}

import com.snowplowanalytics.iglu.schemaddl.redshift.{CompressionEncoding, RedshiftVarchar, AlterType, ZstdEncoding, AlterTable, AddColumn}

import com.snowplowanalytics.snowplow.rdbloader.LoaderError
import com.snowplowanalytics.snowplow.rdbloader.db.{Target, Migration}

import org.specs2.mutable.Specification

import com.snowplowanalytics.snowplow.loader.redshift.db.MigrationSpec
import com.snowplowanalytics.snowplow.rdbloader.SpecHelpers.validConfig

class RedshiftSpec extends Specification {
  import RedshiftSpec.redshift
  "updateTable" should {
    "fail if executed with single schema" in {
      val result = redshift.updateTable(
        SchemaKey("com.acme", "context", "jsonschema", SchemaVer.Full(1,0,0)),
        List("one", "two"),
        MigrationSpec.schemaListSingle
      )

      result must beLeft.like {
        case LoaderError.MigrationError(message) => message must startWith("Illegal State")
        case _ => ko("Error doesn't mention illegal state")
      }
    }

    "create a Block with in-transaction migration" in {
      val result = redshift.updateTable(
        SchemaKey("com.acme", "context", "jsonschema", SchemaVer.Full(1,0,0)),
        List("one", "two"),
        MigrationSpec.schemaListTwo
      )

      val alterTable = AlterTable(
        "atomic.com_acme_context_1",
        AddColumn("three", RedshiftVarchar(4096), None, Some(CompressionEncoding(ZstdEncoding)), None)
      )

      result must beRight.like {
        case Migration.Block(Nil, List(Migration.Item.AddColumn(fragment, Nil)), "atomic", SchemaKey("com.acme", "context", "jsonschema", SchemaVer.Full(1,0,1))) =>
          fragment.toString() must beEqualTo(s"""Fragment("${alterTable.toDdl}")""")
        case _ => ko("Unexpected block found")
      }
    }

    "fail if relevant migration is not found" in {
      val result = redshift.updateTable(
        SchemaKey("com.acme", "context", "jsonschema", SchemaVer.Full(1,0,2)),
        List("one", "two"),
        MigrationSpec.schemaListTwo
      )

      result must beLeft
    }

    "create a Block with pre-transaction migration" in {
      val result = redshift.updateTable(
        SchemaKey("com.acme", "context", "jsonschema", SchemaVer.Full(2,0,0)),
        List("one"),
        MigrationSpec.schemaListThree
      )

      val alterTable = AlterTable(
        "atomic.com_acme_context_2",
        AlterType("one", RedshiftVarchar(64))
      )

      result must beRight.like {
        case Migration.Block(List(Migration.Item.AlterColumn(fragment)), List(), "atomic", SchemaKey("com.acme", "context", "jsonschema", SchemaVer.Full(2,0,1))) =>
          fragment.toString() must beEqualTo(s"""Fragment("${alterTable.toDdl}")""")
        case _ => ko("Unexpected block found")
      }
    }
  }
}

object RedshiftSpec {
  val redshift: Target =
    Redshift.build(validConfig).getOrElse(throw new RuntimeException("Invalid config"))
}