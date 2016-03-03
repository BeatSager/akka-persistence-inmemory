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

package akka.persistence.inmemory.serialization

import akka.actor.{ Props, ActorRef }
import akka.event.LoggingReceive
import akka.persistence.{ RecoveryCompleted, PersistentActor }
import akka.persistence.inmemory.TestSpec
import akka.testkit.TestProbe
import scala.concurrent.duration._

class StoreOnlySerializableMessagesTest extends TestSpec() {

  case class PersistFailure(cause: Throwable, event: Any, seqNr: Long)
  case class PersistRejected(cause: Throwable, event: Any, seqNr: Long)

  class TestActor(val persistenceId: String, recoverProbe: ActorRef, persistFailureProbe: ActorRef, persistRejectedProbe: ActorRef) extends PersistentActor {
    override def receiveRecover: Receive = LoggingReceive {
      case msg ⇒ recoverProbe ! msg
    }

    override def receiveCommand: Receive = LoggingReceive {
      case msg ⇒ persist(msg) { _ ⇒ () }
    }

    override protected def onPersistFailure(cause: Throwable, event: Any, seqNr: Long): Unit =
      persistFailureProbe ! PersistFailure(cause, event, seqNr)

    override protected def onPersistRejected(cause: Throwable, event: Any, seqNr: Long): Unit =
      persistRejectedProbe ! PersistRejected(cause, event, seqNr)
  }

  def withActor(id: String = "1")(f: (ActorRef, TestProbe, TestProbe, TestProbe) ⇒ Unit): Unit = {
    val recoverProbe = TestProbe()
    val persistFailureProbe = TestProbe()
    val persistRejectedProbe = TestProbe()
    val persistentActor = system.actorOf(Props(new TestActor(s"my-$id", recoverProbe.ref, persistFailureProbe.ref, persistRejectedProbe.ref)))
    try f(persistentActor, recoverProbe, persistFailureProbe, persistRejectedProbe) finally cleanup(persistentActor)
  }

  it should "persist a single serializable message" in {
    withActor("1") { (actor, recover, failure, rejected) ⇒
      recover.expectMsg(RecoveryCompleted)
      recover.expectNoMsg(100.millis)
      actor ! "foo" // strings are serializable
      failure.expectNoMsg(100.millis)
      rejected.expectNoMsg(100.millis)
    }

    // the recover cycle
    withActor("1") { (actor, recover, failure, rejected) ⇒
      recover.expectMsg("foo")
      recover.expectMsg(RecoveryCompleted)
      recover.expectNoMsg(100.millis)
      failure.expectNoMsg(100.millis)
      rejected.expectNoMsg(100.millis)
    }
  }

  it should "not persist a single non-serializable message" in {
    class NotSerializable
    withActor("2") { (actor, recover, failure, rejected) ⇒
      recover.expectMsg(RecoveryCompleted)
      recover.expectNoMsg(100.millis)
      actor ! new NotSerializable // the NotSerializable class cannot be serialized
      // the actor should be called the onPersistFailure
      failure.expectMsgPF() {
        case PersistFailure(_, _, _) ⇒
      }
    }

    // the recover cycle, no message should be recovered
    withActor("2") { (actor, recover, failure, rejected) ⇒
      recover.expectMsg(RecoveryCompleted)
      recover.expectNoMsg(100.millis)
    }
  }

  it should "persist only serializable messages" in {
    class NotSerializable
    withActor("3") { (actor, recover, failure, rejected) ⇒
      recover.expectMsg(RecoveryCompleted)
      recover.expectNoMsg(100.millis)
      actor ! "foo"
      actor ! new NotSerializable // the Test class cannot be serialized
      // the actor should be called the onPersistFailure
      failure.expectMsgPF() {
        case PersistFailure(_, _, _) ⇒
      }
      failure.expectNoMsg(100.millis)
    }

    // recover cycle
    withActor("3") { (actor, recover, failure, rejected) ⇒
      recover.expectMsg("foo")
      recover.expectMsg(RecoveryCompleted)
      recover.expectNoMsg(100.millis)
      failure.expectNoMsg(100.millis)
      rejected.expectNoMsg(100.millis)
    }
  }
}
