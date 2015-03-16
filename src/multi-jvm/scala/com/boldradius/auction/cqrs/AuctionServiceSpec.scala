package com.boldradius.cqrs

import java.io.File
import java.util.UUID
import com.boldradius.cqrs.AuctionCommandQueryProtocol._
import scala.concurrent.duration._
import org.apache.commons.io.FileUtils
import com.typesafe.config.ConfigFactory
import akka.actor.ActorIdentity
import akka.actor.Identify
import akka.actor.Props
import akka.cluster.Cluster
import akka.contrib.pattern.ClusterSharding
import akka.persistence.Persistence
import akka.persistence.journal.leveldb.SharedLeveldbJournal
import akka.persistence.journal.leveldb.SharedLeveldbStore
import akka.remote.testconductor.RoleName
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.testkit.ImplicitSender

object AuctionServiceSpec extends MultiNodeConfig {
  val controller = role("controller")
  val node1 = role("node1")
  val node2 = role("node2")

  commonConfig(ConfigFactory.parseString("""
    akka.actor.provider = "akka.cluster.ClusterActorRefProvider"
    akka.persistence.journal.plugin = "akka.persistence.journal.leveldb-shared"
    akka.persistence.journal.leveldb-shared.store {
      native = off
      dir = "target/test-shared-journal"
    }
    akka.persistence.snapshot-store.local.dir = "target/test-snapshots"
    """))
}

class AuctionServiceSpecMultiJvmNode1 extends AuctionServiceSpec
class AuctionServiceSpecMultiJvmNode2 extends AuctionServiceSpec
class AuctionServiceSpecMultiJvmNode3 extends AuctionServiceSpec

class AuctionServiceSpec extends MultiNodeSpec(AuctionServiceSpec)
  with STMultiNodeSpec with ImplicitSender {

  import AuctionServiceSpec._

  def initialParticipants = roles.size

  val storageLocations = List(
    "akka.persistence.journal.leveldb.dir",
    "akka.persistence.journal.leveldb-shared.store.dir",
    "akka.persistence.snapshot-store.local.dir").map(s => new File(system.settings.config.getString(s)))

  override protected def atStartup() {
    runOn(controller) {
      storageLocations.foreach(dir => FileUtils.deleteDirectory(dir))
    }
  }

  override protected def afterTermination() {
    runOn(controller) {
      storageLocations.foreach(dir => FileUtils.deleteDirectory(dir))
    }
  }

  def join(from: RoleName, to: RoleName): Unit = {
    runOn(from) {
      Cluster(system) join node(to).address
      startSharding()
    }
    enterBarrier(from.name + "-joined")
  }

  def startSharding(): Unit = {

    val view = ClusterSharding(system).start(
      typeName = BidView.shardName,
      entryProps = Some(BidView.props),
      idExtractor = BidView.idExtractor,
      shardResolver = BidView.shardResolver)
    ClusterSharding(system).start(
      typeName = BidProcessor.shardName,
      entryProps = Some(BidProcessor.props(view)),
      idExtractor = BidProcessor.idExtractor,
      shardResolver = BidProcessor.shardResolver)
  }

  "Sharded auction service" must {

    "create Auction" in {
      // start the Persistence extension
      Persistence(system)
      runOn(controller) {
        system.actorOf(Props[SharedLeveldbStore], "store")
      }
      enterBarrier("peristence-started")

      runOn(node1, node2) {
        system.actorSelection(node(controller) / "user" / "store") ! Identify(None)
        val sharedStore = expectMsgType[ActorIdentity].ref.get
        SharedLeveldbJournal.setStore(sharedStore, system)
      }

      enterBarrier("after-1")
    }

    "join cluster" in within(15.seconds) {
      join(node1, node1)
      join(node2, node1)
      enterBarrier("after-2")
    }

    val auctionId = UUID.randomUUID().toString

    "start auction" in within(15.seconds) {

      val now = System.currentTimeMillis()

      runOn(node1,node2) {
        val auctionRegion = ClusterSharding(system).shardRegion(BidProcessor.shardName)
        awaitAssert {
          within(5.second) {
            auctionRegion ! StartAuctionCmd(auctionId,now + 1000,now + 1000000,1,"1")
            expectMsg( StartedAuctionAck(auctionId))
          }
        }

      }

      runOn(node1,node2) {
        val auctionViewRegion = ClusterSharding(system).shardRegion(BidView.shardName)
        awaitAssert {
          within(5.second) {
            auctionViewRegion ! WinningBidPriceQuery(auctionId)
            expectMsg( WinningBidPriceResponse(auctionId,1))
          }
        }
      }
      enterBarrier("after-2")
    }

    "bid on auction" in within(15.seconds) {

      runOn(node1,node2) {
        val auctionRegion = ClusterSharding(system).shardRegion(BidProcessor.shardName)
        auctionRegion ! PlaceBidCmd(auctionId,"dave",3)
      }

      runOn(node2,node2) {
        val auctionViewRegion = ClusterSharding(system).shardRegion(BidView.shardName)
        awaitAssert {
          within(5.second) {
            auctionViewRegion ! WinningBidPriceQuery(auctionId)
            expectMsg( WinningBidPriceResponse(auctionId,3))
          }
        }
      }
      enterBarrier("after-3")
    }

  }
}
