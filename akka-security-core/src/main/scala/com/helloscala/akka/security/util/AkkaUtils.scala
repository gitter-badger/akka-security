package com.helloscala.akka.security.util

import akka.actor.typed.receptionist.Receptionist
import akka.actor.typed.receptionist.ServiceKey
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.Props
import akka.actor.typed.RecipientRef
import akka.actor.typed.Scheduler
import akka.actor.typed.SpawnProtocol
import akka.util.Timeout
import com.helloscala.akka.security.exception.AkkaSecurityException

import scala.concurrent.Await
import scala.concurrent.Future

/**
 * @author Yang Jing <a href="mailto:yang.xunjing@qq.com">yangbajing</a>
 * @date 2020-09-19 16:15:26
 */
object AkkaUtils {
  def spawn[T](behavior: Behavior[T], name: String, props: Props = Props.empty)(implicit
      creator: RecipientRef[SpawnProtocol.Command],
      timeout: Timeout,
      scheduler: Scheduler): Future[ActorRef[T]] = {
    creator.ask[ActorRef[T]](replyTo => SpawnProtocol.Spawn(behavior, name, props, replyTo))
  }

  def spawnBlock[T](behavior: Behavior[T], name: String, props: Props = Props.empty)(implicit
      creator: RecipientRef[SpawnProtocol.Command],
      timeout: Timeout,
      scheduler: Scheduler): ActorRef[T] = {
    Await.result(spawn(behavior, name, props), timeout.duration)
  }

  def receptionistFindSet[T](
      serviceKey: ServiceKey[T])(implicit system: ActorSystem[_], timeout: Timeout): Set[ActorRef[T]] = {
    implicit val ec = system.executionContext
    val f = system.receptionist.ask[Receptionist.Listing](Receptionist.Find(serviceKey)).map { listing =>
      if (listing.isForKey(serviceKey)) listing.serviceInstances(serviceKey) else Set[ActorRef[T]]()
    }
    Await.result(f, timeout.duration)
  }

  def receptionistFindOne[T](
      serviceKey: ServiceKey[T])(implicit system: ActorSystem[_], timeout: Timeout): ActorRef[T] = {
    receptionistFindSet(serviceKey).headOption.getOrElse(throw new AkkaSecurityException(s"$serviceKey not found!"))
  }
}
