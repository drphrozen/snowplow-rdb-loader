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
package com.snowplowanalytics.snowplow.loader.redshift

import cats.effect.IO

import org.specs2.mutable.Specification

import com.snowplowanalytics.snowplow.rdbloader.config.Config
import com.snowplowanalytics.snowplow.rdbloader.common.RegionSpec

class ConfigSpec extends Specification {
  import com.snowplowanalytics.snowplow.rdbloader.ConfigSpec._

  "fromString" should {
    "be able to parse extended Redshift config" in {
      val result = getConfig("/redshift.config.reference.hocon", Config.fromString[IO])
      val expected = Config(
        exampleRegion,
        exampleJsonPaths,
        exampleMonitoring,
        exampleQueueName,
        exampleRetryQueue,
        exampleRedshift,
        exampleSchedules,
        exampleTimeouts,
        exampleRetries,
      )
      result must beRight(expected)
    }

    "be able to parse minimal config" in {
      val result = getConfig("/redshift.config.minimal.hocon", testParseConfig)
      val expected = Config(
        RegionSpec.DefaultTestRegion,
        None,
        emptyMonitoring,
        exampleQueueName,
        None,
        exampleRedshift,
        emptySchedules,
        exampleTimeouts,
        exampleRetries.copy(cumulativeBound = None),
      )
      result must beRight(expected)
    }

    "give error when unknown region given" in {
      val result = getConfig("/test.config1.hocon", Config.fromString[IO])
      result must beLeft.like {
        case err => err must contain("unknown-region-1")
      }
    }
  }
}