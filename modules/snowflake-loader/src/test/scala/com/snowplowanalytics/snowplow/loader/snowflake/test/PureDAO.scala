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
package com.snowplowanalytics.snowplow.loader.snowflake.test

import com.snowplowanalytics.snowplow.rdbloader.test.{Pure, PureTransaction, TestState}
import com.snowplowanalytics.snowplow.loader.snowflake.db.{SfDao, Statement}
import doobie.Read

case class PureDAO(executeQuery: Statement => Pure[Any], executeUpdate: Statement => Pure[Int])

object PureDAO {

  def getResult(s: TestState)(query: Statement): Any =
    query match {
      case Statement.TableExists(_, _)       => false
      case Statement.GetColumns(_, _)        => List()
      case Statement.ManifestGet(_, _, _)    => None
      case _                                 => throw new IllegalArgumentException(s"Unexpected query $query with ${s.getLog}")
    }

  def custom(getResult: TestState => Statement => Any): PureDAO = {
    def executeQuery(query: Statement): Pure[Any] =
      Pure((s: TestState) => (s.log(query.toTestString), getResult(s)(query).asInstanceOf[Any]))

    def executeUpdate(sql: Statement): Pure[Int] =
      Pure((s: TestState) => (s.log(sql.toTestString), 1))

    PureDAO(q => executeQuery(q), executeUpdate)
  }

  val init: PureDAO = custom(getResult)

  def interpreter(results: PureDAO): SfDao[Pure] = new SfDao[Pure] {
    def executeUpdate(sql: Statement): Pure[Int] =
      results.executeUpdate(sql)

    def executeQuery[A](query: Statement)(implicit A: Read[A]): Pure[A] =
      results.executeQuery.asInstanceOf[Statement => Pure[A]](query)

    def executeQueryList[A](query: Statement)(implicit A: Read[A]): Pure[List[A]] =
      results.executeQuery.asInstanceOf[Statement => Pure[List[A]]](query)

    def executeQueryOption[A](query: Statement)(implicit A: Read[A]): Pure[Option[A]] =
      results.executeQuery.asInstanceOf[Statement => Pure[Option[A]]](query)

    def rollback: Pure[Unit] =
      Pure.modify(_.log(PureTransaction.Rollback))
  }
}