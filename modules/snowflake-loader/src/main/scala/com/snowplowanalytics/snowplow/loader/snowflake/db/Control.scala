package com.snowplowanalytics.snowplow.loader.snowflake.db

import cats.{Functor, Monad, MonadThrow}
import cats.implicits._

import com.snowplowanalytics.snowplow.loader.snowflake.db.Statement.GetColumns.ShowColumnRow

/** Set of common functions to control DB entities */
object Control {
  def renameTable[C[_]: Functor: SfDao](schema: String, from: String, to: String): C[Unit] = ???

  def tableExists[C[_]: SfDao](dbSchema: String, tableName: String): C[Boolean] =
    SfDao[C].executeQuery[Boolean](Statement.TableExists(dbSchema, tableName))

  /** List all columns in the table */
  def getColumns[C[_]: Monad: SfDao](dbSchema: String, tableName: String): C[List[String]] =
    SfDao[C].executeQueryList[ShowColumnRow](Statement.GetColumns(dbSchema, tableName))
      .map(_.map(_.columnName))

  def resumeWarehouse[C[_]: MonadThrow: SfDao](warehouse: String): C[Unit] =
    SfDao[C].executeUpdate(Statement.WarehouseResume(warehouse)).void
      .recoverWith {
        case _: net.snowflake.client.jdbc.SnowflakeSQLException => MonadThrow[C].unit
      }
}
