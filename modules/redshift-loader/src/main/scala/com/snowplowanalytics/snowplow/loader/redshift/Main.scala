/*
 * Copyright (c) 2012-2022 Snowplow Analytics Ltd. All rights reserved.
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

import cats.effect.{ExitCode, IO, IOApp}
import com.snowplowanalytics.snowplow.loader.redshift.config.RedshiftTarget
import com.snowplowanalytics.snowplow.loader.redshift.config.RedshiftTarget._
import com.snowplowanalytics.snowplow.loader.redshift.dsl.RedshiftEnvironmentBuilder
import com.snowplowanalytics.snowplow.rdbloader.Runner

object Main extends IOApp {
  implicit val builder: RedshiftEnvironmentBuilder[IO] = new RedshiftEnvironmentBuilder
  override def run(args: List[String]): IO[ExitCode]   = Runner.run[IO, RedshiftTarget](args)
}