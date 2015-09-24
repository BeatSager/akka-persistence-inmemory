/*
 * Copyright 2015 Dennis Vriend
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

package akka.persistence.inmemory.query

import akka.actor.{ ActorLogging, ActorRef }
import akka.persistence.Persistence
import akka.persistence.inmemory.journal.InMemoryJournal
import akka.persistence.query.journal.leveldb.DeliveryBuffer
import akka.stream.actor.ActorPublisher
import akka.stream.actor.ActorPublisherMessage.{ Cancel, Request }

private[akka] class AllPersistenceIdsPublisher(liveQuery: Boolean)
    extends ActorPublisher[String] with DeliveryBuffer[String] with ActorLogging {

  val journal: ActorRef = Persistence(context.system).journalFor(InMemoryJournal.Identifier)

  def receive = init

  def init: Receive = {
    case _: Request ⇒
      journal ! InMemoryJournal.AllPersistenceIdsRequest
      context.become(active)
    case Cancel ⇒ context.stop(self)
  }

  def active: Receive = {
    case InMemoryJournal.AllPersistenceIdsResponse(allPersistenceIds) ⇒
      buf ++= allPersistenceIds
      buf = buf.sorted // allPersistenceIds must return a sorted list of ids
      deliverBuf()
      if (!liveQuery && buf.isEmpty)
        onCompleteThenStop()

    case InMemoryJournal.PersistenceIdAdded(persistenceId) ⇒
      if (liveQuery) {
        buf :+= persistenceId
        buf = buf.sorted // allPersistenceIds must return a sorted list of ids
        deliverBuf()
      }

    case _: Request ⇒
      deliverBuf()
      if (!liveQuery && buf.isEmpty)
        onCompleteThenStop()

    case Cancel ⇒ context.stop(self)
  }

}