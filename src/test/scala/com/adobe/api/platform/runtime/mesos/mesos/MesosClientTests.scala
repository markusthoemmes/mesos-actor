/*
 * Copyright 2017 Adobe Systems Incorporated. All rights reserved.
 *
 * This file is licensed to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR REPRESENTATIONS OF ANY KIND,
 * either express or implied.  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.adobe.api.platform.runtime.mesos.mesos

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.Status.Failure
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.StatusCodes
import akka.pattern.ask
import akka.testkit.ImplicitSender
import akka.testkit.TestActorRef
import akka.testkit.TestKit
import akka.testkit.TestProbe
import akka.util.Timeout
import com.adobe.api.platform.runtime.mesos._
import org.apache.mesos.v1.Protos.AgentID
import org.apache.mesos.v1.Protos.FrameworkID
import org.apache.mesos.v1.Protos.OfferID
import org.apache.mesos.v1.Protos.TaskID
import org.apache.mesos.v1.Protos.TaskState
import org.apache.mesos.v1.Protos.TaskStatus
import org.apache.mesos.v1.scheduler.Protos.Call
import org.apache.mesos.v1.scheduler.Protos.Event
import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfterAll
import org.scalatest.FlatSpecLike
import org.scalatest.Matchers
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.junit.JUnitRunner
import scala.concurrent.Future
import scala.concurrent.duration._
@RunWith(classOf[JUnitRunner])
class MesosClientTests
    extends TestKit(ActorSystem("MySpec"))
    with FlatSpecLike
    with ImplicitSender
    with Matchers
    with BeforeAndAfterAll {
  implicit val ec = system.dispatcher
  override def afterAll {
    //TestKit.shutdownActorSystem(system)
  }

  val subscribeCompleteMsg = SubscribeComplete("someid")
  val id = () => {
    "testid"
  }

  behavior of "MesosClientActor actor"

  it should "launch submitted tasks to RUNNING (+ healthy) after offers are received" in {
    val statsListener = TestProbe()
    val mesosClient = TestActorRef(new TestMesosClientActor(id, Some(statsListener.ref), this.testActor))

    //subscribe
    mesosClient ! Subscribe
    mesosClient
      .ask(Subscribe)(Timeout(1.second))
      .mapTo[SubscribeComplete]
      .onComplete(complete => {
        system.log.info("subscribe completed successfully...")
      })
    expectMsg(subscribeCompleteMsg)

    //submit the task
    mesosClient ! SubmitTask(
      TaskDef(
        "taskId1",
        "taskId1",
        "fake-docker-image",
        0.1,
        256,
        List(8080),
        healthCheckParams = Some(HealthCheckConfig(healthCheckPortIndex = 0)),
        commandDef = Some(CommandDef(environment = Map("__OW_API_HOST" -> "192.168.99.100")))))
    //receive offers
    mesosClient ! ProtobufUtil.getOffers("/offer1.json")

    //verify that ACCEPT was sent
    expectMsg("ACCEPT_SENT")

    //verify agentOfferHistory
    val stats = statsListener.expectMsgType[MesosAgentStats].stats

    stats.size shouldBe 3
    stats.keys shouldBe Set("192.168.99.100", "192.168.99.101", "192.168.99.102")
    stats.foreach(_ match {
      case (host, AgentStats(mem, cpus, ports, _)) =>
        if (host == "192.168.99.100") {
          cpus shouldBe 0.9
          ports shouldBe 199
          mem shouldBe 2902.0
        } else if (host == "192.168.99.101") {
          cpus shouldBe 1.0
          ports shouldBe 202
          mem shouldBe 2902.0
        } else {
          cpus shouldBe 0.9
          ports shouldBe 199
          mem shouldBe 2902.0
        }
    })

    //wait for post accept

    val agentId = AgentID
      .newBuilder()
      .setValue("db6b062d-84e3-4a2e-a8c5-98ffa944a304-S0")
      .build()
    //receive the task details after successful launch
    system.log.info("sending UPDATE")

    mesosClient ! org.apache.mesos.v1.scheduler.Protos.Event.Update
      .newBuilder()
      .setStatus(
        TaskStatus
          .newBuilder()
          .setTaskId(TaskID.newBuilder().setValue("taskId1"))
          .setState(TaskState.TASK_STAGING)
          .setAgentId(agentId)
          .build())
      .build()
    //verify that UPDATE was received

    mesosClient ! org.apache.mesos.v1.scheduler.Protos.Event.Update
      .newBuilder()
      .setStatus(
        TaskStatus
          .newBuilder()
          .setTaskId(TaskID.newBuilder().setValue("taskId1"))
          .setState(TaskState.TASK_RUNNING)
          .setAgentId(agentId)
          .setHealthy(false)
          .build())
      .build()

    //verify that UPDATE was received
    //verify that task is in RUNNING (but NOT healthy) state

    //verify that UPDATE was received
    //verify that task is in RUNNING (AND healthy) state

    mesosClient ! org.apache.mesos.v1.scheduler.Protos.Event.Update
      .newBuilder()
      .setStatus(
        TaskStatus
          .newBuilder()
          .setTaskId(TaskID.newBuilder().setValue("taskId1"))
          .setState(TaskState.TASK_RUNNING)
          .setAgentId(agentId)
          .setHealthy(true)
          .build())
      .build()
    val runningTaskStatus = TaskStatus
      .newBuilder()
      .setTaskId(TaskID.newBuilder().setValue("taskId1"))
      .setState(TaskState.TASK_RUNNING)
      .setAgentId(agentId)
      .setHealthy(true)
      .build()
//      val runningTaskInfo = ProtobufUtil.getTaskInfo("/taskdetails.json")
    val expectedTaskDetails = Running("taskId1", agentId.getValue, runningTaskStatus, "192.168.99.100", List(11001))

    expectMsg(expectedTaskDetails)

    mesosClient ! org.apache.mesos.v1.scheduler.Protos.Event.Update
      .newBuilder()
      .setStatus(
        TaskStatus
          .newBuilder()
          .setTaskId(TaskID.newBuilder().setValue("taskId1"))
          .setState(TaskState.TASK_FAILED)
          .setAgentId(agentId)
          .setHealthy(false)
          .build())
      .build()

    val failedTaskDetails = Failed("taskId1", agentId.getValue)

    expectMsg(failedTaskDetails)

    //we may get multiple failed events
    mesosClient ! org.apache.mesos.v1.scheduler.Protos.Event.Update
      .newBuilder()
      .setStatus(
        TaskStatus
          .newBuilder()
          .setTaskId(TaskID.newBuilder().setValue("taskId1"))
          .setState(TaskState.TASK_FAILED)
          .setAgentId(agentId)
          .setHealthy(false)
          .build())
      .build()
  }
  it should "reject submitted tasks to after max offer cycles" in {

    val statsListener = TestProbe()
    val mesosClient = TestActorRef(new TestMesosClientActor(id, Some(statsListener.ref), this.testActor))

    //subscribe
    mesosClient ! Subscribe
    mesosClient
      .ask(Subscribe)(Timeout(1.second))
      .mapTo[SubscribeComplete]
      .onComplete(complete => {
        system.log.info("subscribe completed successfully...")
      })
    expectMsg(subscribeCompleteMsg)

    //submit the task
    mesosClient ! SubmitTask(
      TaskDef(
        "taskId1",
        "taskId1",
        "fake-docker-image",
        0.1,
        256,
        List(8080),
        healthCheckParams = Some(HealthCheckConfig(healthCheckPortIndex = 0)),
        commandDef = Some(CommandDef(environment = Map("__OW_API_HOST" -> "192.168.99.100")))))
    //receive 4 empty offers (configured max offers is 3, then capacity failure)
    mesosClient ! ProtobufUtil.getOffers("/emptyoffer.json")
    mesosClient ! ProtobufUtil.getOffers("/emptyoffer.json")
    mesosClient ! ProtobufUtil.getOffers("/emptyoffer.json")
    mesosClient ! ProtobufUtil.getOffers("/emptyoffer.json")

    //expect this CapacityFailure
    val failure = expectMsgType[Failure]
    failure.cause shouldBe a[CapacityFailure]
  }
  it should "tolerate TASK_FAILED after receiving DeleteTask" in {

    val mesosClient = TestActorRef(new TestMesosClientActor(id, probe = this.testActor))

    //subscribe
    mesosClient ! Subscribe
    mesosClient
      .ask(Subscribe)(Timeout(1.second))
      .mapTo[SubscribeComplete]
      .onComplete(complete => {
        system.log.info("subscribe completed successfully...")
      })
    expectMsg(subscribeCompleteMsg)

    //submit the task
    mesosClient ! SubmitTask(
      TaskDef(
        "taskId1",
        "taskId1",
        "fake-docker-image",
        0.1,
        256,
        List(8080),
        healthCheckParams = Some(HealthCheckConfig(healthCheckPortIndex = 0)),
        commandDef = Some(CommandDef(environment = Map("__OW_API_HOST" -> "192.168.99.100")))))
    //receive offers
    mesosClient ! ProtobufUtil.getOffers("/offer1.json")

    //verify that ACCEPT was sent
    expectMsg("ACCEPT_SENT")
    //wait for post accept

    val agentId = AgentID
      .newBuilder()
      .setValue("db6b062d-84e3-4a2e-a8c5-98ffa944a304-S0")
      .build()
    //receive the task details after successful launch
    system.log.info("sending UPDATE")

    mesosClient ! org.apache.mesos.v1.scheduler.Protos.Event.Update
      .newBuilder()
      .setStatus(
        TaskStatus
          .newBuilder()
          .setTaskId(TaskID.newBuilder().setValue("taskId1"))
          .setState(TaskState.TASK_STAGING)
          .setAgentId(agentId)
          .build())
      .build()
    //verify that UPDATE was received

    mesosClient ! org.apache.mesos.v1.scheduler.Protos.Event.Update
      .newBuilder()
      .setStatus(
        TaskStatus
          .newBuilder()
          .setTaskId(TaskID.newBuilder().setValue("taskId1"))
          .setState(TaskState.TASK_RUNNING)
          .setAgentId(agentId)
          .setHealthy(false)
          .build())
      .build()

    //verify that UPDATE was received
    //verify that task is in RUNNING (but NOT healthy) state

    //verify that UPDATE was received
    //verify that task is in RUNNING (AND healthy) state

    mesosClient ! org.apache.mesos.v1.scheduler.Protos.Event.Update
      .newBuilder()
      .setStatus(
        TaskStatus
          .newBuilder()
          .setTaskId(TaskID.newBuilder().setValue("taskId1"))
          .setState(TaskState.TASK_RUNNING)
          .setAgentId(agentId)
          .setHealthy(true)
          .build())
      .build()
    val runningTaskStatus = TaskStatus
      .newBuilder()
      .setTaskId(TaskID.newBuilder().setValue("taskId1"))
      .setState(TaskState.TASK_RUNNING)
      .setAgentId(agentId)
      .setHealthy(true)
      .build()
    val expectedTaskDetails = Running("taskId1", agentId.getValue, runningTaskStatus, "192.168.99.100", List(11001))

    expectMsg(expectedTaskDetails)

    //send DeleteTask
    val deleteComplete = mesosClient.ask(DeleteTask("taskId1"))(10.seconds)

    //receive TASK_FAILED
    mesosClient ! org.apache.mesos.v1.scheduler.Protos.Event.Update
      .newBuilder()
      .setStatus(
        TaskStatus
          .newBuilder()
          .setTaskId(TaskID.newBuilder().setValue("taskId1"))
          .setState(TaskState.TASK_FAILED)
          .setAgentId(agentId)
          .setHealthy(false)
          .build())
      .build()

    ScalaFutures.whenReady(deleteComplete.failed) { e =>
      e shouldBe a[MesosException]
    }
  }
  it should "match multiple offers at once" in {
    val statsListener = TestProbe()
    val mesosClient = TestActorRef(new TestMesosClientActor(id, Some(statsListener.ref), this.testActor))

    //subscribe
    mesosClient ! Subscribe
    mesosClient
      .ask(Subscribe)(Timeout(1.second))
      .mapTo[SubscribeComplete]
      .onComplete(complete => {
        system.log.info("subscribe completed successfully...")
      })
    expectMsg(subscribeCompleteMsg)

    //submit the task
    mesosClient ! SubmitTask(
      TaskDef(
        "taskId1",
        "taskId1",
        "fake-docker-image",
        0.1,
        2500,
        List(8080),
        healthCheckParams = Some(HealthCheckConfig(healthCheckPortIndex = 0)),
        commandDef = Some(CommandDef(environment = Map("__OW_API_HOST" -> "192.168.99.100")))))
    mesosClient ! SubmitTask(
      TaskDef(
        "taskId2",
        "taskId2",
        "fake-docker-image",
        0.1,
        2500,
        List(8080),
        healthCheckParams = Some(HealthCheckConfig(healthCheckPortIndex = 0)),
        commandDef = Some(CommandDef(environment = Map("__OW_API_HOST" -> "192.168.99.100")))))
    mesosClient ! SubmitTask(
      TaskDef(
        "taskId3",
        "taskId3",
        "fake-docker-image",
        0.1,
        256,
        List(8080),
        healthCheckParams = Some(HealthCheckConfig(healthCheckPortIndex = 0)),
        commandDef = Some(CommandDef(environment = Map("__OW_API_HOST" -> "192.168.99.100")))))
    //receive offers
    mesosClient ! ProtobufUtil.getOffers("/offer-multiple.json")

    //verify that ACCEPT was sent twice
    expectMsg("ACCEPT_SENT")
    expectMsg("ACCEPT_SENT")

    //verify agentOfferHistory
    val stats = statsListener.expectMsgType[MesosAgentStats].stats

    stats.size shouldBe 3
    stats.keys shouldBe Set("192.168.99.100", "192.168.99.101", "192.168.99.102")
    stats.foreach(_ match {
      case ("192.168.99.100", AgentStats(mem, cpus, ports, _)) =>
        cpus shouldBe 0.8
        ports shouldBe 199
        mem shouldBe 2912.0
      case ("192.168.99.101", AgentStats(mem, cpus, ports, _)) =>
        cpus shouldBe 1.0
        ports shouldBe 199
        mem shouldBe 2901.0
      case ("192.168.99.102", AgentStats(mem, cpus, ports, _)) =>
        cpus shouldBe 0.9
        ports shouldBe 199
        mem shouldBe 2902.0
      case _ => fail("unexpected offer host!")
    })

    //wait for post accept

    val agentId2 = AgentID
      .newBuilder()
      .setValue("db6b062d-84e3-4a2e-a8c5-98ffa944a304-S1")
      .build()
    val agentId3 = AgentID
      .newBuilder()
      .setValue("db6b062d-84e3-4a2e-a8c5-98ffa944a304-S2")
      .build()
    //receive the task details after successful launch
    system.log.info("sending UPDATE")

    mesosClient ! org.apache.mesos.v1.scheduler.Protos.Event.Update
      .newBuilder()
      .setStatus(
        TaskStatus
          .newBuilder()
          .setTaskId(TaskID.newBuilder().setValue("taskId1"))
          .setState(TaskState.TASK_STAGING)
          .setAgentId(agentId3)
          .build())
      .build()
    //verify that UPDATE was received

    mesosClient ! org.apache.mesos.v1.scheduler.Protos.Event.Update
      .newBuilder()
      .setStatus(
        TaskStatus
          .newBuilder()
          .setTaskId(TaskID.newBuilder().setValue("taskId1"))
          .setState(TaskState.TASK_RUNNING)
          .setAgentId(agentId3)
          .setHealthy(false)
          .build())
      .build()

    //verify that UPDATE was received
    //verify that task is in RUNNING (but NOT healthy) state

    //verify that UPDATE was received
    //verify that task is in RUNNING (AND healthy) state

    mesosClient ! org.apache.mesos.v1.scheduler.Protos.Event.Update
      .newBuilder()
      .setStatus(
        TaskStatus
          .newBuilder()
          .setTaskId(TaskID.newBuilder().setValue("taskId1"))
          .setState(TaskState.TASK_RUNNING)
          .setAgentId(agentId3)
          .setHealthy(true)
          .build())
      .build()
    val runningTaskStatus = TaskStatus
      .newBuilder()
      .setTaskId(TaskID.newBuilder().setValue("taskId1"))
      .setState(TaskState.TASK_RUNNING)
      .setAgentId(agentId3)
      .setHealthy(true)
      .build()
    //      val runningTaskInfo = ProtobufUtil.getTaskInfo("/taskdetails.json")
    val expectedTaskDetails = Running("taskId1", agentId3.getValue, runningTaskStatus, "192.168.99.102", List(11001))

    //TODO match the tasks in order of receipt!
    expectMsg(expectedTaskDetails)

  }
  it should "hold offers and match them immediately on task submission" in {
    val statsListener = TestProbe()
    val mesosClient = TestActorRef(new TestMesosClientActor(id, Some(statsListener.ref), this.testActor))

    //subscribe
    mesosClient ! Subscribe
    mesosClient
      .ask(Subscribe)(Timeout(1.second))
      .mapTo[SubscribeComplete]
      .onComplete(complete => {
        system.log.info("subscribe completed successfully...")
      })
    expectMsg(subscribeCompleteMsg)

    //submit the task
    mesosClient ! SubmitTask(
      TaskDef(
        "taskId1",
        "taskId1",
        "fake-docker-image",
        0.1,
        2500,
        List(8080),
        healthCheckParams = Some(HealthCheckConfig(healthCheckPortIndex = 0)),
        commandDef = Some(CommandDef(environment = Map("__OW_API_HOST" -> "192.168.99.100")))))
    //receive offers
    mesosClient ! ProtobufUtil.getOffers("/offer-multiple.json")

    //verify that ACCEPT was sent
    expectMsg("ACCEPT_SENT")

    //verify agentOfferHistory
    val stats = statsListener.expectMsgType[MesosAgentStats].stats

    stats.size shouldBe 3
    stats.keys shouldBe Set("192.168.99.100", "192.168.99.101", "192.168.99.102")
    stats.foreach(_ match {
      case ("192.168.99.100", AgentStats(mem, cpus, ports, _)) =>
        cpus shouldBe 0.8
        ports shouldBe 199
        mem shouldBe 2912.0
      case ("192.168.99.101", AgentStats(mem, cpus, ports, _)) =>
        cpus shouldBe 1.0
        ports shouldBe 199
        mem shouldBe 2901.0

      case ("192.168.99.102", AgentStats(mem, cpus, ports, _)) =>
        cpus shouldBe 0.9
        ports shouldBe 199
        mem shouldBe 2902.0
      case _ => fail("unexpected offer host!")
    })

    //wait for post accept

    val agentId2 = AgentID
      .newBuilder()
      .setValue("db6b062d-84e3-4a2e-a8c5-98ffa944a304-S1")
      .build()

    //receive the task details after successful launch
    system.log.info("sending UPDATE")

    mesosClient ! org.apache.mesos.v1.scheduler.Protos.Event.Update
      .newBuilder()
      .setStatus(
        TaskStatus
          .newBuilder()
          .setTaskId(TaskID.newBuilder().setValue("taskId1"))
          .setState(TaskState.TASK_STAGING)
          .setAgentId(agentId2)
          .build())
      .build()
    //verify that UPDATE was received

    mesosClient ! org.apache.mesos.v1.scheduler.Protos.Event.Update
      .newBuilder()
      .setStatus(
        TaskStatus
          .newBuilder()
          .setTaskId(TaskID.newBuilder().setValue("taskId1"))
          .setState(TaskState.TASK_RUNNING)
          .setAgentId(agentId2)
          .setHealthy(false)
          .build())
      .build()

    //verify that UPDATE was received
    //verify that task is in RUNNING (but NOT healthy) state

    //verify that UPDATE was received
    //verify that task is in RUNNING (AND healthy) state

    mesosClient ! org.apache.mesos.v1.scheduler.Protos.Event.Update
      .newBuilder()
      .setStatus(
        TaskStatus
          .newBuilder()
          .setTaskId(TaskID.newBuilder().setValue("taskId1"))
          .setState(TaskState.TASK_RUNNING)
          .setAgentId(agentId2)
          .setHealthy(true)
          .build())
      .build()
    val runningTaskStatus = TaskStatus
      .newBuilder()
      .setTaskId(TaskID.newBuilder().setValue("taskId1"))
      .setState(TaskState.TASK_RUNNING)
      .setAgentId(agentId2)
      .setHealthy(true)
      .build()
    val expectedTaskDetails = Running("taskId1", agentId2.getValue, runningTaskStatus, "192.168.99.101", List(12001))

    expectMsg(expectedTaskDetails)

    //submit additional tasks that should match the held offers
    mesosClient ! SubmitTask(
      TaskDef(
        "taskId2",
        "taskId2",
        "fake-docker-image",
        0.1,
        2500, //matches 102 agent -> 412mb remain
        List(8080),
        healthCheckParams = Some(HealthCheckConfig(healthCheckPortIndex = 0)),
        commandDef = Some(CommandDef(environment = Map("__OW_API_HOST" -> "192.168.99.100")))))
    mesosClient ! SubmitTask(
      TaskDef(
        "taskId3",
        "taskId3",
        "fake-docker-image",
        0.1,
        2000, //matches 100 agent -> 912mb remain
        List(8080),
        healthCheckParams = Some(HealthCheckConfig(healthCheckPortIndex = 0)),
        commandDef = Some(CommandDef(environment = Map("__OW_API_HOST" -> "192.168.99.100")))))

    //sending an empty offer will trigger reuse of held offers also, and since even though the held offer is least used node, it will get used after 3 offer cycles regardless
    mesosClient ! ProtobufUtil.getOffers("/emptyoffer.json")
    expectMsg("ACCEPT_SENT")
    expectMsg("ACCEPT_SENT")
  }

  it should "throw a DockerRunFailureException when docker run exits with 125 status" in {

    val mesosClient = TestActorRef(new TestMesosClientActor(id, probe = this.testActor))

    //subscribe
    mesosClient ! Subscribe
    mesosClient
      .ask(Subscribe)(Timeout(1.second))
      .mapTo[SubscribeComplete]
      .onComplete(complete => {
        system.log.info("subscribe completed successfully...")
      })
    expectMsg(subscribeCompleteMsg)

    //submit the task
    mesosClient ! SubmitTask(
      TaskDef(
        "taskId1",
        "taskId1",
        "fake-docker-image",
        0.1,
        256,
        List(8080),
        healthCheckParams = Some(HealthCheckConfig(healthCheckPortIndex = 0)),
        commandDef = Some(CommandDef(environment = Map("__OW_API_HOST" -> "192.168.99.100")))))
    //receive offers
    mesosClient ! ProtobufUtil.getOffers("/offer1.json")

    //verify that ACCEPT was sent
    expectMsg("ACCEPT_SENT")
    //wait for post accept

    val agentId = AgentID
      .newBuilder()
      .setValue("db6b062d-84e3-4a2e-a8c5-98ffa944a304-S0")
      .build()
    //receive the task details after successful launch
    system.log.info("sending UPDATE")

    mesosClient ! org.apache.mesos.v1.scheduler.Protos.Event.Update
      .newBuilder()
      .setStatus(
        TaskStatus
          .newBuilder()
          .setTaskId(TaskID.newBuilder().setValue("taskId1"))
          .setState(TaskState.TASK_STAGING)
          .setAgentId(agentId)
          .build())
      .build()
    //verify that UPDATE was received

    mesosClient ! org.apache.mesos.v1.scheduler.Protos.Event.Update
      .newBuilder()
      .setStatus(
        TaskStatus
          .newBuilder()
          .setTaskId(TaskID.newBuilder().setValue("taskId1"))
          .setState(TaskState.TASK_FAILED)
          .setMessage(MesosClient.DOCKER_RUN_FAILURE_MESSAGE)
          .setAgentId(agentId)
          .setHealthy(false)
          .build())
      .build()

    //verify that UPDATE was received
    //verify that task is in RUNNING (but NOT healthy) state

    //verify that UPDATE was received
    //verify that task is in RUNNING (AND healthy) state

    mesosClient ! org.apache.mesos.v1.scheduler.Protos.Event.Update
      .newBuilder()
      .setStatus(
        TaskStatus
          .newBuilder()
          .setTaskId(TaskID.newBuilder().setValue("taskId1"))
          .setState(TaskState.TASK_RUNNING)
          .setAgentId(agentId)
          .setHealthy(true)
          .build())
      .build()
    val runningTaskStatus = TaskStatus
      .newBuilder()
      .setTaskId(TaskID.newBuilder().setValue("taskId1"))
      .setState(TaskState.TASK_RUNNING)
      .setAgentId(agentId)
      .setHealthy(true)
      .build()

    expectMsg(Failure(DockerRunFailure(MesosClient.DOCKER_RUN_FAILURE_MESSAGE)))

  }

  it should "throw a DockerPullFailureException when docker pull fails" in {

    val mesosClient = TestActorRef(new TestMesosClientActor(id, probe = this.testActor))

    //subscribe
    mesosClient ! Subscribe
    mesosClient
      .ask(Subscribe)(Timeout(1.second))
      .mapTo[SubscribeComplete]
      .onComplete(complete => {
        system.log.info("subscribe completed successfully...")
      })
    expectMsg(subscribeCompleteMsg)

    //submit the task
    mesosClient ! SubmitTask(
      TaskDef(
        "taskId1",
        "taskId1",
        "fake-docker-image",
        0.1,
        256,
        List(8080),
        healthCheckParams = Some(HealthCheckConfig(healthCheckPortIndex = 0)),
        commandDef = Some(CommandDef(environment = Map("__OW_API_HOST" -> "192.168.99.100")))))
    //receive offers
    mesosClient ! ProtobufUtil.getOffers("/offer1.json")

    //verify that ACCEPT was sent
    expectMsg("ACCEPT_SENT")
    //wait for post accept

    val agentId = AgentID
      .newBuilder()
      .setValue("db6b062d-84e3-4a2e-a8c5-98ffa944a304-S0")
      .build()
    //receive the task details after successful launch
    system.log.info("sending UPDATE")

    mesosClient ! org.apache.mesos.v1.scheduler.Protos.Event.Update
      .newBuilder()
      .setStatus(
        TaskStatus
          .newBuilder()
          .setTaskId(TaskID.newBuilder().setValue("taskId1"))
          .setState(TaskState.TASK_STAGING)
          .setAgentId(agentId)
          .build())
      .build()

    val failMsg = MesosClient.DOCKER_PULL_FAILURE_MESSAGE + " some image reference"
    mesosClient ! org.apache.mesos.v1.scheduler.Protos.Event.Update
      .newBuilder()
      .setStatus(
        TaskStatus
          .newBuilder()
          .setTaskId(TaskID.newBuilder().setValue("taskId1"))
          .setState(TaskState.TASK_FAILED)
          .setMessage(failMsg)
          .setAgentId(agentId)
          .setHealthy(false)
          .build())
      .build()
    expectMsg(Failure(DockerPullFailure(failMsg)))
  }
  it should "hold offers and remove them on RESCIND" in {
    val statsListener = TestProbe()
    val mesosClient = TestActorRef(new TestMesosClientActor(id, Some(statsListener.ref), this.testActor))

    //subscribe
    mesosClient ! Subscribe
    mesosClient
      .ask(Subscribe)(Timeout(1.second))
      .mapTo[SubscribeComplete]
      .onComplete(complete => {
        system.log.info("subscribe completed successfully...")
      })
    expectMsg(subscribeCompleteMsg)

    //receive offers
    mesosClient ! ProtobufUtil.getOffers("/offer-multiple.json")

    //verify agentOfferHistory
    val stats = statsListener.expectMsgType[MesosAgentStats].stats

    stats.size shouldBe 3
    stats.keys shouldBe Set("192.168.99.100", "192.168.99.101", "192.168.99.102")
    stats.foreach(_ match {
      case ("192.168.99.100", AgentStats(mem, cpus, ports, _)) =>
        cpus shouldBe 0.8
        ports shouldBe 199
        mem shouldBe 2912.0
      case ("192.168.99.101", AgentStats(mem, cpus, ports, _)) =>
        cpus shouldBe 1.0
        ports shouldBe 199
        mem shouldBe 2901.0

      case ("192.168.99.102", AgentStats(mem, cpus, ports, _)) =>
        cpus shouldBe 0.9
        ports shouldBe 199
        mem shouldBe 2902.0
      case _ => fail("unexpected offer host!")
    })

    //send RESCIND
    val rescind = Event
      .newBuilder()
      .setType(Event.Type.RESCIND)
      .setRescind(
        Event.Rescind
          .newBuilder()
          .setOfferId(OfferID.newBuilder().setValue("7168e411-c3e4-4e29-b292-9b12eda4aaca-O61"))
          .build())
      .build()
    mesosClient ! rescind
    //send RESCIND
    val rescind2 = Event
      .newBuilder()
      .setType(Event.Type.RESCIND)
      .setRescind(
        Event.Rescind
          .newBuilder()
          .setOfferId(OfferID.newBuilder().setValue("7168e411-c3e4-4e29-b292-9b12eda4aaca-O60"))
          .build())
      .build()
    mesosClient ! rescind2
    //send RESCIND
    val rescind3 = Event
      .newBuilder()
      .setType(Event.Type.RESCIND)
      .setRescind(
        Event.Rescind
          .newBuilder()
          .setOfferId(OfferID.newBuilder().setValue("7168e411-c3e4-4e29-b292-9b12eda4aaca-O59"))
          .build())
      .build()
    mesosClient ! rescind3
    //send RESCIND
    val rescind4 = Event
      .newBuilder()
      .setType(Event.Type.RESCIND)
      .setRescind(
        Event.Rescind
          .newBuilder()
          .setOfferId(OfferID.newBuilder().setValue("7168e411-c3e4-4e29-b292-9b12eda4aaca-O58"))
          .build())
      .build()
    mesosClient ! rescind4
    //submit the task
    mesosClient ! SubmitTask(
      TaskDef(
        "taskId1",
        "taskId1",
        "fake-docker-image",
        0.1,
        256,
        List(8080),
        healthCheckParams = Some(HealthCheckConfig(healthCheckPortIndex = 0)),
        commandDef = Some(CommandDef(environment = Map("__OW_API_HOST" -> "192.168.99.100")))))
    //expaect no launch because offers were rescinded
    expectNoMsg()
  }
  class TestMesosClientActor(override val id: () => String,
                             override val listener: Option[ActorRef] = None,
                             probe: ActorRef)
      extends MesosClientActor()
      with MesosClientConnection {

    override val frameworkName: String = "testframework"
    override val master: String = "none"
    override val role: String = "*"
    override val taskMatcher: TaskMatcher = new DefaultTaskMatcher()
    override val taskBuilder: TaskBuilder = new DefaultTaskBuilder()
    override val failoverTimeoutSeconds = 0.seconds
    override val autoSubscribe: Boolean = false
    override val tasks: TaskStore = new LocalTaskStore
    override val refuseSeconds: Double = 1.0
    override val heartbeatMaxFailures: Int = 2
    override val config = MesosActorConfig(5.seconds, 30.seconds, Some(3), true, 5.seconds, 30.seconds, true, 10)

    override def exec(call: Call): Future[HttpResponse] = {
      log.info(s"sending ${call.getType}")
      call.getType match {
        case Call.Type.ACCEPT =>
          logger.info("got ACCEPT; sending ACCEPT_SENT")
          //sender() ! "ACCEPT_SENT"
          probe ! "ACCEPT_SENT"
          Future.successful(HttpResponse(StatusCodes.OK))
        case _ => Future.failed(new Exception(s"unhandled call type ${call.getType}"))
      }
    }

    override def subscribe(frameworkID: FrameworkID,
                           frameworkName: String,
                           failoverTimeout: Double): Future[SubscribeComplete] = {
      Future.successful(subscribeCompleteMsg)
    }
  }

}
