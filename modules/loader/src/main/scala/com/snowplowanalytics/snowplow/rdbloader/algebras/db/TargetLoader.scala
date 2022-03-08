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
package com.snowplowanalytics.snowplow.rdbloader.algebras.db

import com.snowplowanalytics.snowplow.rdbloader.discovery.DataDiscovery

/**
  * Module containing specific for target loading
  * Works in three steps:
  * 1. Discover all data in shredded.good
  * 2. Construct SQL-statements
  * 3. Load data into target
  * Errors of discovering steps are accumulating
  */
trait TargetLoader[C[_]] {
  def run(discovery: DataDiscovery): C[Unit]
}

object TargetLoader {
  def apply[C[_]](implicit ev: TargetLoader[C]): TargetLoader[C] = ev
}