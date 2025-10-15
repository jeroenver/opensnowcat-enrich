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
package com.snowplowanalytics.snowplow.enrich.eventbridge

import cats.effect.{Blocker, ContextShift, IO, Resource, Timer}
import com.snowplowanalytics.snowplow.enrich.common.fs2.config.io.Input
import fs2.{Pipe, Stream}
import io.circe.Json

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object utils {

  private val executionContext: ExecutionContext = ExecutionContext.global
  implicit val ioContextShift: ContextShift[IO] = IO.contextShift(executionContext)
  implicit val ioTimer: Timer[IO] = IO.timer(executionContext)

  sealed trait OutputRow
  object OutputRow {
    // TODO: we should use Event, we need to find a way to parse the flattened events
    final case class Good(event: Json) extends OutputRow
    // TODO: we should use BadRow, we need to find a way to parse it
    final case class Bad(badRow: Json) extends OutputRow
  }

  def mkEnrichPipe(
    localstackPort: Int,
    uuid: String
  ): Resource[IO, Pipe[IO, Array[Byte], OutputRow]] =
    for {
      blocker <- Blocker[IO]
      streams = IntegrationTestConfig.getStreams(uuid)
      kinesisRawSink <- com.snowplowanalytics.snowplow.enrich.eventbridge.Sink
                          .init[IO](blocker, IntegrationTestConfig.kinesisOutputStreamConfig(localstackPort, streams.kinesisInput))
    } yield {
      val kinesisGoodOutput = asGood(
        outputStream(blocker, IntegrationTestConfig.kinesisInputStreamConfig(localstackPort, streams.kinesisOutputGood))
      )
      val kinesisBadOutput = asBad(
        outputStream(blocker, IntegrationTestConfig.kinesisInputStreamConfig(localstackPort, streams.kinesisOutputBad))
      )

      (collectorPayloads: Stream[IO, Array[Byte]]) =>
        kinesisGoodOutput
          .merge(kinesisBadOutput)
          .interruptAfter(3.minutes)
          .concurrently(collectorPayloads.evalMap(bytes => kinesisRawSink(List(bytes))))
    }

  private def outputStream(blocker: Blocker, config: Input.Kinesis): Stream[IO, Array[Byte]] =
    com.snowplowanalytics.snowplow.enrich.eventbridge.Source
      .init[IO](blocker, config, IntegrationTestConfig.monitoring)
      .map(com.snowplowanalytics.snowplow.enrich.eventbridge.EventbridgeRun.getPayload)

  private def asGood(source: Stream[IO, Array[Byte]]): Stream[IO, OutputRow.Good] =
    source.map { bytes =>
      val s = new String(bytes)
      // this is an eventbridge event, we need to extract the `detail` entry from it
      val parsed = io.circe.parser.parse(s) match {
        case Right(json) =>
          json.hcursor
            .downField("detail")
            .as[Json] match {
            case Right(r) => r
            case Left(e) => throw new RuntimeException(s"Can't parse enriched events from eventbridge: $e, json: $json")
          }
        case Left(e) => throw new RuntimeException(s"Can't parse enriched event [$s]. Error: $e")
      }
      OutputRow.Good(parsed)
    }

  private def asBad(source: Stream[IO, Array[Byte]]): Stream[IO, OutputRow.Bad] =
    source.map { bytes =>
      val s = new String(bytes)
      // this is an eventbridge event, we need to extract the `detail` entry from it
      val parsed = io.circe.parser.parse(s) match {
        case Right(json) =>
          json.hcursor
            .downField("detail")
            .as[io.circe.Json]
            .getOrElse(throw new RuntimeException(s"Can't parse bad row from eventbridge: $s"))

        case Left(e) => throw new RuntimeException(s"Can't parse bad row [$s]. Error: $e")
      }
      OutputRow.Bad(parsed)
    }

  def parseOutput(output: List[OutputRow], testName: String): (List[Json], List[Json]) = {
    val good = output.collect { case OutputRow.Good(e) => e }
    println(s"[$testName] Bad rows:")
    val bad = output.collect { case OutputRow.Bad(b) =>
      println(s"[$testName] ${b.toString()}")
      b
    }
    (good, bad)
  }
}
