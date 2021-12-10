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
package com.snowplowanalytics.snowplow.rdbloader

import cats.Applicative
import cats.implicits._

import cats.effect.{ExitCode, IOApp, Concurrent, IO, Timer, Clock}
import cats.effect.implicits._

import fs2.Stream

import org.typelevel.log4cats.slf4j.Slf4jLogger
import com.snowplowanalytics.snowplow.rdbloader.db.Manifest
import com.snowplowanalytics.snowplow.rdbloader.dsl._
import com.snowplowanalytics.snowplow.rdbloader.config.CliConfig
import com.snowplowanalytics.snowplow.rdbloader.discovery.{ DataDiscovery, NoOperation }
import com.snowplowanalytics.snowplow.rdbloader.loading.Load.load
import com.snowplowanalytics.snowplow.rdbloader.state.Control


object Main extends IOApp {

  private implicit val LoggerName =
    Logging.LoggerName(getClass.getSimpleName.stripSuffix("$"))

  def run(argv: List[String]): IO[ExitCode] =
    for {
      parsed <- CliConfig.parse[IO](argv).value
      res <- parsed match {
        case Right(cli) =>
          Environment.initialize[IO](cli).use { env =>
            import env._

            loggingF.info(s"RDB Loader ${generated.BuildInfo.version} has started. Listening ${cli.config.messageQueue}") *>
              process[IO](cli, control)
                .compile
                .drain
                .as(ExitCode.Success)
                .handleErrorWith(handleFailure[IO])
          }
        case Left(error) =>
          val logger = Slf4jLogger.getLogger[IO]
          logger.error("Configuration error") *> logger.error(error).as(ExitCode(2))
      }
    } yield res

  /**
   * Main application workflow, responsible for discovering new data via message queue
   * and processing this data with loaders
   *
   * @param cli whole app configuration
   * @param control various stateful controllers
   * @return endless stream waiting for messages
   */
  def process[F[_]: Concurrent: AWS: Clock: Iglu: Cache: Logging: Timer: Monitoring: JDBC](cli: CliConfig, control: Control[F]): Stream[F, Unit] = {
    val folderMonitoring: Stream[F, Unit] =
      FolderMonitoring.run[F](cli.config.monitoring.folders, cli.config.storage, control.isBusy)
    val noOpScheduling: Stream[F, Unit] =
      NoOperation.run(cli.config.schedules.noOperation, control.makePaused, control.signal.map(_.loading))

    // TODO: Currently, steps are deactivated with making them empty.
    // Remove them from the codebase properly.
    Stream.eval_(Manifest.initialize[F](cli.config.storage)) ++
      DataDiscovery
        .discover[F](cli.config.copy(steps = Set.empty), control.incrementMessages)
        .pauseWhen[F](control.isBusy)
        .evalMap { discovery =>
          val prepare = for {
            _        <- StateMonitoring.run(control.get, discovery.extend).background
            makeBusy  = control.makeBusy
            _        <- makeBusy(discovery.data.origin.base)
          } yield ()
          
          val loading: F[Unit] = prepare.use { _ =>
            load[F](cli.config.copy(steps = Set.empty), control.setStage, discovery).rethrowT *> control.incrementLoaded
          }

          // Catches both connection acquisition and loading errors
          loading.onError { case error =>
            val msg = s"Could not load a folder (base ${discovery.data.discovery.base}), trying to ack the SQS command"
            Monitoring[F].alert(error, discovery.data.discovery.base) *>
              Logging[F].info(msg) *>  // No need for ERROR - it will be printed downstream in handleFailure
              discovery.ack
          }
        }
        .merge(folderMonitoring)
        .merge(noOpScheduling)
  }

  /**
   * The application can throw in several places and all those exceptions must be
   * rethrown and sent downstream. This function makes sure that every exception
   * resulting into Loader restart is:
   * 1. We always print ERROR in the end
   * 2. We send a Sentry exception if Sentry is configured
   * 3. We attempt to send the failure via tracker
   */
  def handleFailure[F[_]: Applicative: Logging: Monitoring](error: Throwable): F[ExitCode] =
    Logging[F].error(error)("Loader shutting down") *> // Making sure we always have last ERROR printed
      Monitoring[F].alert(Monitoring.AlertPayload.error(error.toString)) *>
      Monitoring[F].trackException(error) *>
      Monitoring[F].track(LoaderError.RuntimeError(error.getMessage).asLeft).as(ExitCode.Error)
}
