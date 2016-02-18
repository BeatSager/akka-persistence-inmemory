/*
 * Copyright 2016 Dennis Vriend
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.persistence.inmemory.dao

import akka.actor.ActorRef
import akka.persistence.inmemory.dao.SnapshotDao.SnapshotData
import akka.stream.Materializer
import akka.util.Timeout
import akka.pattern.ask

import scala.concurrent.{ ExecutionContext, Future }

object SnapshotDao {
  case class SnapshotData(persistenceId: String, sequenceNumber: Long, created: Long, snapshot: Array[Byte])

  /**
   * Factory method
   */
  def apply(db: ActorRef)(implicit timeout: Timeout, ec: ExecutionContext, mat: Materializer): SnapshotDao =
    new InMemorySnapshotDao(db)
}

trait SnapshotDao {
  def deleteAllSnapshots(persistenceId: String): Future[Unit]

  def deleteUpToMaxSequenceNr(persistenceId: String, maxSequenceNr: Long): Future[Unit]

  def deleteUpToMaxTimestamp(persistenceId: String, maxTimestamp: Long): Future[Unit]

  def deleteUpToMaxSequenceNrAndMaxTimestamp(persistenceId: String, maxSequenceNr: Long, maxTimestamp: Long): Future[Unit]

  def snapshotForMaxSequenceNr(persistenceId: String): Future[Option[SnapshotData]]

  def snapshotForMaxSequenceNr(persistenceId: String, sequenceNr: Long): Future[Option[SnapshotData]]

  def snapshotForMaxTimestamp(persistenceId: String, timestamp: Long): Future[Option[SnapshotData]]

  def snapshotForMaxSequenceNrAndMaxTimestamp(persistenceId: String, sequenceNr: Long, timestamp: Long): Future[Option[SnapshotData]]

  def delete(persistenceId: String, sequenceNr: Long): Future[Unit]

  def save(persistenceId: String, sequenceNr: Long, timestamp: Long, snapshot: Array[Byte]): Future[Unit]
}

class InMemorySnapshotDao(db: ActorRef)(implicit timeout: Timeout, ec: ExecutionContext, mat: Materializer) extends SnapshotDao {
  import InMemorySnapshotStorage._
  override def delete(persistenceId: String, sequenceNr: Long): Future[Unit] =
    (db ? Delete(persistenceId, sequenceNr)).map(_ ⇒ ())

  override def deleteAllSnapshots(persistenceId: String): Future[Unit] =
    (db ? DeleteAllSnapshots(persistenceId)).map(_ ⇒ ())

  override def deleteUpToMaxSequenceNrAndMaxTimestamp(persistenceId: String, maxSequenceNr: Long, maxTimestamp: Long): Future[Unit] =
    (db ? DeleteUpToMaxSequenceNrAndMaxTimestamp(persistenceId, maxSequenceNr, maxTimestamp)).map(_ ⇒ ())

  override def deleteUpToMaxTimestamp(persistenceId: String, maxTimestamp: Long): Future[Unit] =
    (db ? DeleteUpToMaxTimestamp(persistenceId, maxTimestamp)).map(_ ⇒ ())

  override def deleteUpToMaxSequenceNr(persistenceId: String, maxSequenceNr: Long): Future[Unit] =
    (db ? DeleteUpToMaxSequenceNr(persistenceId, maxSequenceNr)).map(_ ⇒ ())

  override def save(persistenceId: String, sequenceNr: Long, timestamp: Long, snapshot: Array[Byte]): Future[Unit] =
    (db ? Save(persistenceId, sequenceNr, timestamp, snapshot)).map(_ ⇒ ())

  override def snapshotForMaxSequenceNr(persistenceId: String): Future[Option[SnapshotData]] =
    (db ? SnapshotForMaxSequenceNr(persistenceId, Long.MaxValue)).mapTo[Option[SnapshotData]]

  override def snapshotForMaxSequenceNr(persistenceId: String, sequenceNr: Long): Future[Option[SnapshotData]] =
    (db ? SnapshotForMaxSequenceNr(persistenceId, sequenceNr)).mapTo[Option[SnapshotData]]

  override def snapshotForMaxTimestamp(persistenceId: String, timestamp: Long): Future[Option[SnapshotData]] =
    (db ? SnapshotForMaxTimestamp(persistenceId, timestamp)).mapTo[Option[SnapshotData]]

  override def snapshotForMaxSequenceNrAndMaxTimestamp(persistenceId: String, sequenceNr: Long, timestamp: Long): Future[Option[SnapshotData]] =
    (db ? SnapshotForMaxSequenceNrAndMaxTimestamp(persistenceId, sequenceNr, timestamp)).mapTo[Option[SnapshotData]]
}