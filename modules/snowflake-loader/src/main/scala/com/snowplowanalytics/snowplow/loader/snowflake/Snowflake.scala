package com.snowplowanalytics.snowplow.loader.snowflake

import java.sql.Timestamp

import cats.implicits._
import cats.data.NonEmptyList

import doobie.Fragment
import doobie.implicits._

import io.circe.syntax._

import com.snowplowanalytics.iglu.core.SchemaKey

import com.snowplowanalytics.snowplow.rdbloader.{LoaderError, LoadStatements}
import com.snowplowanalytics.snowplow.rdbloader.loading.EventsTable
import com.snowplowanalytics.snowplow.rdbloader.config.{Config, StorageTarget}
import com.snowplowanalytics.snowplow.rdbloader.discovery.{DiscoveryFailure, DataDiscovery, ShreddedType}
import com.snowplowanalytics.snowplow.rdbloader.db.{Target, Statement}
import com.snowplowanalytics.snowplow.rdbloader.db.Migration.{Item, Block, NoPreStatements, NoStatements}
import com.snowplowanalytics.iglu.schemaddl.migrations.{Migration, SchemaList}

import com.snowplowanalytics.snowplow.loader.snowflake.ast.{Column, SnowflakeDatatype, PrimaryKeyConstraint}
import com.snowplowanalytics.snowplow.loader.snowflake.db.Statement.{CreateTable, AddColumn}
import com.snowplowanalytics.snowplow.rdbloader.common.LoaderMessage.SnowplowEntity

object Snowflake {

  val EventFieldSeparator = Fragment.const0("\t")

  val AlertingTempTableName = "rdb_folder_monitoring"

  def build(config: Config[StorageTarget]): Either[String, Target] = {
    config.storage match {
      case StorageTarget.Snowflake(region, username, role, password, account, warehouse, database, schema, stageName, appName, folderMonitoringStage, maxError, jdbcHost) =>
        val result = new Target {
          // TODO: not going to work because we need to migrate the main table
          def updateTable(current: SchemaKey, columns: List[String], state: SchemaList): Either[LoaderError, Block] = {
            val shreddedType: ShreddedType = ???    // TODO: we need to know the snowplow entity type for column name
            val isContext = shreddedType.info.snowplowEntity == SnowplowEntity.Context
            val columnType = if (isContext) SnowflakeDatatype.JsonArray else SnowflakeDatatype.JsonObject
            val columnName = (if (isContext) "context_" else "unstruct_event_") ++ shreddedType.getTableName
            val addColumnSql = AddColumn(schema, EventsTable.MainName, columnName, columnType)
            val addColumn = Item.AddColumn(addColumnSql.toFragment, Nil)
            Right(Block(List(addColumn), Nil, schema, state.latest.schemaKey))
          }

          def getLoadStatements(discovery: DataDiscovery): LoadStatements =
            NonEmptyList(Statement.EventsCopy(discovery.base, discovery.compression), Nil)

          // Technically, Snowflake Loader cannot create new tables
          def createTable(schemas: SchemaList): Block =
            Block(Nil, Nil, schema, schemas.latest.schemaKey)

          def getManifest: Statement =
            Statement.CreateTable(getManifestDef(schema).toFragment)

          def toFragment(statement: Statement): Fragment =
            statement match {
              case Statement.Begin => sql"BEGIN"
              case Statement.Commit => sql"COMMIT"
              case Statement.Abort => sql"ABORT"
              case Statement.Select1 => sql"SELECT 1"     // OK

              case Statement.CreateAlertingTempTable =>   // OK
                val frTableName = Fragment.const(AlertingTempTableName)
                sql"CREATE TEMPORARY TABLE $frTableName ( run_id VARCHAR )"
              case Statement.DropAlertingTempTable =>
                val frTableName = Fragment.const(AlertingTempTableName)
                sql"DROP TABLE IF EXISTS $frTableName"
              case Statement.FoldersMinusManifest =>
                val frTableName = Fragment.const(AlertingTempTableName)
                val frManifest = Fragment.const(s"${schema}.manifest")
                sql"SELECT run_id FROM $frTableName MINUS SELECT base FROM $frManifest"
              case Statement.FoldersCopy(source) =>
                val frTableName = Fragment.const(AlertingTempTableName)
                val frPath      = Fragment.const0(source)   // TODO: did we really need stage
                sql"COPY INTO $frTableName FROM $frPath FILE_FORMAT = (TYPE = CSV)"

              case Statement.EventsCopy(path, compression) =>
                val frTableName = Fragment.const(EventsTable.MainName)
                val frPath      = Fragment.const0(path)   // TODO: did we really need stage
                sql"COPY INTO $frTableName FROM $frPath FILE_FORMAT = (TYPE = CSV)"
              case Statement.ShreddedCopy(_, _) =>
                throw new IllegalStateException("Snowflake Loader does not support loading shredded data")

              case Statement.CreateTransient =>
                Fragment.const0(s"CREATE TABLE ${EventsTable.TransitTable(schema).withSchema} ( LIKE ${EventsTable.AtomicEvents(schema).withSchema} )")
              case Statement.DropTransient =>
                Fragment.const0(s"DROP TABLE ${EventsTable.TransitTable(schema).withSchema}")
              case Statement.AppendTransient =>
                Fragment.const0(s"ALTER TABLE ${EventsTable.AtomicEvents(schema).withSchema} APPEND FROM ${EventsTable.TransitTable(schema).withSchema}")

              case Statement.TableExists(tableName) => // OK
                sql"""|SELECT EXISTS (
                      |  SELECT 1
                      |  FROM   information_schema.tables
                      |  WHERE  TABLE_SCHEMA = $schema
                      |  AND    TABLE_NAME = $tableName)
                      | AS exists""".stripMargin
              case Statement.GetVersion(tableName) =>
                throw new IllegalStateException("Snowflake Loader does not support table versioning")

              case Statement.RenameTable(from, to) =>
                Fragment.const0(s"ALTER TABLE $from RENAME TO $to")
              case Statement.SetSchema =>
                Fragment.const0(s"SET search_path TO ${schema}")    // TODO: does it work?
              case Statement.GetColumns(tableName) =>
                val frTableName = Fragment.const0(s"$schema.$tableName")
                // Since querying information_schema is significantly slower,
                // 'show columns' is used. Visit following link for more information:
                // https://community.snowflake.com/s/article/metadata-operations-throttling
                sql"SHOW COLUMNS IN TABLE $frTableName"
              case Statement.ManifestAdd(message) =>
                val tableName = Fragment.const(s"${schema}.manifest")
                val types = message.types.asJson.noSpaces
                // Redshift JDBC doesn't accept java.time.Instant
                sql"""INSERT INTO $tableName
                  (base, types, shredding_started, shredding_completed,
                  min_collector_tstamp, max_collector_tstamp, ingestion_tstamp,
                  compression, processor_artifact, processor_version, count_good)
                  VALUES (${message.base}, $types,
                  ${Timestamp.from(message.timestamps.jobStarted)}, ${Timestamp.from(message.timestamps.jobCompleted)},
                  ${message.timestamps.min.map(Timestamp.from)}, ${message.timestamps.max.map(Timestamp.from)},
                  getdate(),
                  ${message.compression.asString}, ${message.processor.artifact}, ${message.processor.version}, ${message.count})"""
              case Statement.ManifestGet(base) =>
                sql"""SELECT ingestion_tstamp,
                   base, types, shredding_started, shredding_completed,
                   min_collector_tstamp, max_collector_tstamp,
                   compression, processor_artifact, processor_version, count_good
                   FROM ${Fragment.const0(s"$schema.$ManifestName")} WHERE base = $base"""

              case Statement.CreateTable(ddl) =>
                Fragment.const0(ddl)
              case Statement.CommentOn(table, comment) =>
                throw new IllegalStateException("Snowflake Loader does not support table versioning")
              case Statement.DdlFile(ddl) =>
                Fragment.const0(ddl)
              case Statement.AlterTable(ddl) =>
                Fragment.const0(ddl)
            }
        }

        Right(result)
      case other =>
        Left(s"Invalid State: trying to build Snowflake interpreter with unrecognized config (${other.driver} driver)")
    }
  }

  val ManifestName = "manifest"

  val ManifestPK = PrimaryKeyConstraint("base_pk", "base")

  val ManifestColumns = List(
    Column("base", SnowflakeDatatype.Varchar(512), notNull = true, unique = true),
    Column("types", SnowflakeDatatype.JsonArray, notNull = true),
    Column("shredding_started", SnowflakeDatatype.Timestamp, notNull = true),
    Column("shredding_completed", SnowflakeDatatype.Timestamp, notNull = true),
    Column("min_collector_tstamp", SnowflakeDatatype.Timestamp),
    Column("max_collector_tstamp", SnowflakeDatatype.Timestamp),
    Column("ingestion_tstamp", SnowflakeDatatype.Timestamp, notNull = true),
    Column("compression", SnowflakeDatatype.Varchar(16), notNull = true),
    Column("processor_artifact", SnowflakeDatatype.Varchar(64), notNull = true),
    Column("processor_version", SnowflakeDatatype.Varchar(32), notNull = true),
    Column("count_good", SnowflakeDatatype.Integer)
  )

  /** Add `schema` to otherwise static definition of manifest table */
  def getManifestDef(schema: String): CreateTable =
    CreateTable(schema, ManifestName, ManifestColumns, Some(ManifestPK))
}
