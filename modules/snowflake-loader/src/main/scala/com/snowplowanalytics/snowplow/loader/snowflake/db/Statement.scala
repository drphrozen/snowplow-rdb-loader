/*
 * Copyright (c) 2022-2022 Snowplow Analytics Ltd. All rights reserved.
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
package com.snowplowanalytics.snowplow.loader.snowflake.db

import doobie.Fragment
import doobie.implicits._

import cats.implicits._

import com.snowplowanalytics.snowplow.loader.snowflake.ast._

trait Statement {
  /** Transform to doobie `Fragment`, closer to the end-of-the-world */
  def toFragment: Fragment
}

object Statement {
  case class CreateTable(schema: String,
                         name: String,
                         columns: List[Column],
                         primaryKey: Option[PrimaryKeyConstraint],
                         temporary: Boolean = false) extends Statement {
    def toFragment: Fragment = {
      val frConstraint = primaryKey.map(c => fr", ${c.toDdl}").getOrElse(Fragment.empty)
      val frCols = columns.map(_.toDdl).intercalate(fr",")
      val frTemp = if (temporary) Fragment.const("TEMPORARY") else Fragment.empty
      val frTableName = Fragment.const0(s"$schema.$name")
      sql"""CREATE ${frTemp}TABLE IF NOT EXISTS $frTableName (
           $frCols$frConstraint
         )"""
    }
  }

  case class DropTable(schema: String, table: String) extends Statement {
    def toFragment: Fragment = {
      val frTableName = Fragment.const(s"$schema.$table")
      sql"DROP TABLE IF EXISTS $frTableName"
    }
  }

  case class AddColumn(schema: String,
                       table: String,
                       column: String,
                       datatype: SnowflakeDatatype) extends Statement {
    def toFragment: Fragment = {
      val frTableName = Fragment.const0(s"$schema.$table")
      val frColumn = Fragment.const0(column)
      sql"ALTER TABLE $frTableName ADD COLUMN $frColumn ${datatype.toDdl}"
    }
  }

  case class CopyInto(schema: String,
                      table: String,
                      stageName: String,
                      columns: List[String],
                      loadPath: String,
                      maxError: Option[Int]) extends Statement {
    def toFragment: Fragment = {
      // TODO: Add auth option
      val frOnError = maxError match {
        case Some(value) => Fragment.const0(s"ON_ERROR = SKIP_FILE_$value")
        case None => Fragment.empty
      }
      val frCopy = Fragment.const0(s"$schema.$table($columnsForCopy)")
      val frSelectColumns = Fragment.const0(columnsForSelect)
      val frSelectTable = Fragment.const0(s"@$schema.$stageName/$loadPath")
      sql"""|COPY INTO $frCopy
            |FROM (
            |  SELECT $frSelectColumns FROM $frSelectTable
            |)
            |$frOnError""".stripMargin
    }

    def columnsForCopy: String = columns.mkString(",")
    def columnsForSelect: String = columns.map(c => s"$$1:$c").mkString(",")
  }



  // Alerting
  case class FoldersMinusManifest(schema: String,
                                  alertTable: String,
                                  manifestTable: String) extends Statement {
    def toFragment: Fragment = {
      val frTableName = Fragment.const(s"$schema.$alertTable")
      val frManifest  = Fragment.const(s"$schema.$manifestTable")
      sql"SELECT run_id FROM $frTableName MINUS SELECT base FROM $frManifest"
    }
  }
  case class FoldersCopy(schema: String,
                         table: String,
                         stageName: String,
                         loadPath: String) extends Statement {
    def toFragment: Fragment = {
      val frTableName = Fragment.const(table)
      val frPath      = Fragment.const0(s"@$schema.$stageName/$loadPath")
      sql"COPY INTO $frTableName FROM $frPath FILE_FORMAT = (TYPE = CSV)"
    }
  }

  case class WarehouseResume(warehouse: String) extends Statement {
    def toFragment: Fragment = {
      val frWarehouse = Fragment.const0(warehouse)
      sql"ALTER WAREHOUSE $frWarehouse RESUME"
    }
  }
}
