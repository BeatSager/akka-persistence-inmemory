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

package akka.persistence.inmemory.journal

import akka.actor.Terminated
import akka.persistence.inmemory.dao.JournalDao
import akka.persistence.inmemory.serialization.SerializationFacade
import akka.persistence.journal.AsyncWriteJournal
import akka.persistence.query.EventEnvelope
import akka.persistence.{ AtomicWrite, PersistentRepr }
import akka.stream.Materializer
import akka.stream.scaladsl.Source

import scala.collection.immutable
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.Try

object InMemoryJournal {
  final val Identifier = "inmemory-journal"

  final case class EventsByPersistenceIdAndTagRequest(persistenceId: String, tag: String)
  final case class EventsByTagRequest(tag: String)
  final case class EventsByPersistenceIdRequest(persistenceId: String)
  final case class EventAppended(envelope: EventEnvelope)

  case object AllPersistenceIdsRequest
  final case class PersistenceIdAdded(persistenceId: String)
}

trait InMemoryAsyncWriteJournalLike extends AsyncWriteJournal
    with AllPersistenceIdsSubscriberRegistry
    with EventsByPersistenceIdRegistry
    with EventsByTagSubscriberRegistry
    with EventsByPersistenceIdTagSubscriberRegistry {

  def journalDao: JournalDao

  implicit def mat: Materializer

  implicit def ec: ExecutionContext

  def serializationFacade: SerializationFacade

  override def asyncWriteMessages(messages: immutable.Seq[AtomicWrite]): Future[immutable.Seq[Try[Unit]]] = {
    val persistenceIdsInNewSetOfAtomicWrites = messages.map(_.persistenceId).toList
    for {
      xs ← if (hasAllPersistenceIdsSubscribers)
        journalDao.persistenceIds(persistenceIdsInNewSetOfAtomicWrites)
          .map(persistenceIdsInNewSetOfAtomicWrites.diff(_))
      else Future.successful(List.empty[String])
      xy ← Source.fromIterator(() ⇒ messages.iterator)
        .via(serializationFacade.serialize)
        .via(journalDao.writeFlow)
        .via(addAllPersistenceIdsFlow(xs))
        .via(eventsByPersistenceIdFlow(messages))
        .via(eventsByTagFlow(messages))
        .via(eventsByPersistenceIdAndTagFlow(messages))
        .map(_.map(_ ⇒ ()))
        .runFold(List.empty[Try[Unit]])(_ :+ _)
    } yield xy
  }

  override def asyncDeleteMessagesTo(persistenceId: String, toSequenceNr: Long): Future[Unit] =
    journalDao.delete(persistenceId, toSequenceNr)

  override def asyncReadHighestSequenceNr(persistenceId: String, fromSequenceNr: Long): Future[Long] =
    journalDao.highestSequenceNr(persistenceId, fromSequenceNr)

  override def asyncReplayMessages(persistenceId: String, fromSequenceNr: Long, toSequenceNr: Long, max: Long)(recoveryCallback: (PersistentRepr) ⇒ Unit): Future[Unit] =
    journalDao.messages(persistenceId, fromSequenceNr, toSequenceNr, max)
      .via(serializationFacade.deserializeRepr)
      .mapAsync(1)(deserializedRepr ⇒ Future.fromTry(deserializedRepr))
      .runForeach(recoveryCallback)
      .map(_ ⇒ ())

  def handleTerminated: Receive = {
    case Terminated(ref) ⇒
      sendAllPersistenceIdsSubscriberTerminated(ref)
      sendEventsByPersistenceIdSubscriberTerminated(ref)
      sendEventsByTagSubscriberTerminated(ref)
      sendEventsByPersistenceIdAndTagSubscriberTerminated(ref)
  }

  override def receivePluginInternal: Receive =
    handleTerminated.orElse(receiveAllPersistenceIdsSubscriber)
      .orElse(receiveEventsByPersistenceIdRegistry)
      .orElse(receiveEventsByTagRegistry)
      .orElse(receiveEventsByPersistenceIdAndTagRegistry)
}
