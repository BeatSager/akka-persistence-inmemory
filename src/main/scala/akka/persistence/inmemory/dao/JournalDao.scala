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

import akka.NotUsed
import akka.actor.ActorRef
import akka.pattern.ask
import akka.persistence.inmemory.serialization.Serialized
import akka.stream.Materializer
import akka.stream.scaladsl._
import akka.util.Timeout

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

object JournalDao {
  /**
   * Factory method
   */
  def apply(db: ActorRef)(implicit timeout: Timeout, ec: ExecutionContext, mat: Materializer): JournalDao = new InMemoryJournalDao(db)
}

trait WriteMessagesFacade {
  def writeMessages: Flow[Try[Iterable[Serialized]], Try[Iterable[Serialized]], NotUsed]
}

class FlowGraphWriteMessagesFacade(journalDao: JournalDao)(implicit ec: ExecutionContext, mat: Materializer) extends WriteMessagesFacade {
  def writeMessages: Flow[Try[Iterable[Serialized]], Try[Iterable[Serialized]], NotUsed] = Flow[Try[Iterable[Serialized]]].mapAsync(1) {
    case element @ Success(xs) ⇒ journalDao.writeList(xs).map(_ ⇒ element)
    case element @ Failure(t)  ⇒ Future.successful(element)
  }
}

class InMemoryJournalDao(db: ActorRef)(implicit timeout: Timeout, ec: ExecutionContext, mat: Materializer) {

  import InMemoryJournalStorage._

  val writeMessagesFacade: WriteMessagesFacade = new FlowGraphWriteMessagesFacade(this)

  def allPersistenceIds: Future[Set[String]] =
    (db ? AllPersistenceIds).mapTo[Set[String]]

  def writeFlow: Flow[Try[Iterable[Serialized]], Try[Iterable[Serialized]], NotUsed] =
    Flow[Try[Iterable[Serialized]]].via(writeMessagesFacade.writeMessages)

  def eventsByPersistenceIdAndTag(persistenceId: String, tag: String, offset: Long): Source[Array[Byte], NotUsed] =
    Source.fromFuture((db ? EventsByTag(tag, offset)).mapTo[List[JournalEntry]])
      .mapConcat(identity).map(_.serialized.serialized)

  def highestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] =
    (db ? HighestSequenceNr(persistenceId, fromSequenceNr)).mapTo[Long]

  def eventsByTag(tag: String, offset: Long): Source[Array[Byte], NotUsed] =
    Source.fromFuture((db ? EventsByTag(tag, offset)).mapTo[List[JournalEntry]])
      .mapConcat(identity).map(_.serialized.serialized)

  def writeList(xs: Iterable[Serialized]): Future[Unit] =
    (db ? WriteList(xs)).map(_ ⇒ ())

  def delete(persistenceId: String, toSequenceNr: Long): Future[Unit] =
    (db ? Delete(persistenceId, toSequenceNr)).map(_ ⇒ ())

  def messages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long): Source[Array[Byte], NotUsed] = {
    Source.fromFuture((db ? Messages(persistenceId, fromSequenceNr, toSequenceNr, max)).mapTo[List[JournalEntry]])
      .mapConcat(identity).map(_.serialized.serialized)
  }
}
