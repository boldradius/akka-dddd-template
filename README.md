# akka-dddd-template
Akka DDDD template using CQRS/ES with a Distributed Domain

Scala Version = 2.11.6

Akka Version = 2.3.9

Spray Version = 1.3.1


## Background

### Distributed Domain Driven Design

### CQRS/ES  Command Query Responsibility Segregation / Event Sourcing

This is a pattern that uses Command and Query objects to apply the [CQS](http://en.wikipedia.org/wiki/Command%E2%80%93query_separation) principle
        for modifying and retrieving data.

Event Sourcing is an architectural pattern in which state is tracked with an immutable event log instead of
destructive updates (mutable).
        
## Getting Started

To get this application going, you will need to:

*   Set up the datastore
*   Boot the cluster nodes
*   Boot the Http microservice node

### DataStore

This application requires a distributed journal. Storage backends for journals and snapshot stores are pluggable in Akka persistence. In this case we are using [Cassandra](http://cassandra.apache.org/download/).
You can find other journal plugins [here](http://akka.io/community/?_ga=1.264939791.1443869017.1408561680).

The datastore is specified in **application.conf**

cassandra-journal.contact-points = ["127.0.0.1"]

cassandra-snapshot-store.contact-points = ["127.0.0.1"]

As you can see, the default is localhost. In a cloud deployment, you could add several addresses to a cassandra cluster.

This application uses a simple domain to demonstrate CQRS and event sourcing with Akka Persistence. This domain is an online auction:

        final case class Bid(price:Double, buyer:String, timeStamp:Long)

        final case class Auction(auctionId:String,
                             startTime:Long,
                             endTime:Long,
                             initialPrice:Double,
                             acceptedBids:List[Bid],
                             refusedBids:List[Bid],
                             ended:Boolean)

This is a distributed application, leveraging **Akka Cluster**.

The **Command** path of this application is illustrated by the creation of an auction, and placing bids.

The **Query** path of this application is illustrated by the querying of winning bid and bid history.

In order to distribute and segregate these paths, we leverage  **Akka Cluster**, as well as **Cluster Sharding**.

Cluster Sharding enables the  distribution of the command and query actors across several nodes in the cluster,
supporting interaction using their logical identifier, without having to care about their physical location in the cluster.

### Cluster Nodes

You must first boot some cluster nodes (as many as you want). Running locally, these are distinguished by port  eg:[2551,2552,...].

This cluster must specify one or more **seed nodes** in **application.conf**

    akka.cluster {
    seed-nodes = [
    "akka.tcp://ClusterSystem@127.0.0.1:2551",
    "akka.tcp://ClusterSystem@127.0.0.1:2552"]
    }

The Cluster Nodes are bootstrapped in **ClusterNode.scala**.

To boot each cluster node locally:

    sbt 'runMain com.boldradius.cqrs.ClusterNodeApp nodeIpAddress port'

for example:

    sbt 'runMain com.boldradius.cqrs.ClusterNodeApp 127.0.0.1 2551'

### Http Microservice Node

The HTTP front end is implemented as a **Spray** microservice and is bootstrapped in **HttpApp.scala**.It participates in the Cluster, but as a proxy.

To run the microservice locally:

    sbt 'runMain com.boldradius.cqrs.HttpApp httpIpAddress httpPort akkaIpAddres akkaPort'

for example:

    sbt 'runMain com.boldradius.cqrs.HttpApp 127.0.0.1 9000 127.0.0.1 0'


The HTTP API enables the user to:

*   Create an Auction
*   Place a did
*   Query for the current winning bid
*   Query for the bid history

#### Create Auction

        POST http://127.0.0.1:9000/startAuction

        {"auctionId":"123",
        "start":"2015-01-20-16:25",
        "end":"2015-07-20-16:35",
        "initialPrice" : 2,
        "prodId" : "3"}

#### Place Bid

    POST http://127.0.0.1:9000/bid

    {"auctionId":"123",
    "buyer":"dave",
    "bidPrice":6}

#### Query for the current winning bid

    GET http://localhost:8080/winningBid/123

#### Query for the bid history

    http://localhost:8080/bidHistory/123

### Spray service fowards to the cluster

The trait **HttpAuctionServiceRoute.scala** implements a route that takes ActorRefs (one for command and query) as input.
Upon receiving an Http request, it either sends a command message to the **command** actor, or a query message to the **query** actor.

     def route(command: ActorRef, query:ActorRef) = {
         post {
            path("startAuction") {
                extract(_.request) { e =>
                    entity(as[StartAuctionDto]) {
                        auction => onComplete(
                            (command ? StartAuctionCmd(auction.auctionId,....

## Exploring the Command path in the Cluster

The command path is implemented in **BidProcessor.scala**. This is a **PersistentActor** that receives commands:

    def initial: Receive = {
        case a@StartAuctionCmd(id, start, end, initialPrice, prodId) => ...
    }

    def takingBids(state: Auction): Receive = {
                case a@PlaceBidCmd(id, buyer, bidPrice) => ...
    }

and produces events, writing them to the event journal, and notifying the **Query** Path of the updated journal:

    val event = AuctionStartedEvt(id, start, end, initialPrice, prodId)   // the event to be persisted
    persistAsync(event) { evt =>                                          // block that will run once event has been written to journal
    readRegion ! Update(await = true)                                   // update the Query path
    auctionStateMaybe = startMaybeState(id, start, end, initialPrice)   // update internal state
    ...
    }

This actor is cluster sharded on auctionId as follows:

    val idExtractor: ShardRegion.IdExtractor = {
        case m: AuctionCmd => (m.auctionId, m)
    }

    val shardResolver: ShardRegion.ShardResolver = msg => msg match {
        case m: AuctionCmd => (math.abs(m.auctionId.hashCode) % 100).toString
    }

    val shardName: String = "BidProcessor"

This means, there is only one instance of this actor in the cluster, and all commands with the same  **auctionId** will be routed to the same actor.

If this actor receives no commands for 1 minute, it will **passivate** ( a pattern enabling the parent to stop the actor, in order to reduce memory consumption without losing any commands it is currently processing):

    /** passivate the entity when no activity */
    context.setReceiveTimeout(1 minute)     // this will send a ReceiveTimeout message after one minute, if no other messages come in

The timeout is handled in the **Passivation.scala** trait:

    protected def withPassivation(receive: Receive): Receive = receive.orElse{
        // tell parent actor to send us a poisinpill
        case ReceiveTimeout => context.parent ! Passivate(stopMessage = PoisonPill)

        // stop
        case PoisonPill => context.stop(self)
    }


If this actor fails, or is passivated, and then is required again (to handle a command), the cluster will spin it up, and it will replay the event journal. In this case we make use a var: auctionRecoverStateMaybe to capture the state while we replay. When the replay is finished, the actor is notified with the RecoveryCompleted message and we can then "become" appropriately to reflect this state. 

    def receiveRecover: Receive = {
        case evt:AuctionStartedEvt =>
                auctionRecoverStateMaybe = Some(Auction(evt.auctionId,evt.started,evt.end,evt.initialPrice,Nil,Nil,false))

            case evt: AuctionEvt => {
              auctionRecoverStateMaybe = auctionRecoverStateMaybe.map(state =>
                updateState(evt.logDebug("receiveRecover" + _.toString),state))
            }
        
            // Once recovery is complete, check the state to become the appropriate behaviour
            case RecoveryCompleted => {
              auctionRecoverStateMaybe.fold[Unit]({}) { auctionState =>
                if (auctionState.logDebug("receiveRecover RecoveryCompleted state: " + _.toString).ended)
                  context.become(passivate(auctionClosed(auctionState)).orElse(unknownCommand))
                else {
                  launchLifetime(auctionState.endTime)
                  context.become(passivate(takingBids(auctionState)).orElse(unknownCommand))
                }
              }
            }

## Exploring the Query path in the Cluster

The Queries are handled in a different Actor: **BidView.scala**. This is a **PersistentView** that handles query messages, or prompts from it's companion **PersistentActor** to update itself.

**BidView.scala** is linked to the **BidProcessor.scala** event journal via it's **persistenceId**

    override val persistenceId: String = "BidProcessor" + "-" + self.path.name

This means it has access to this event journal, and can maintain, and recover state from this journal.

It is possible for a PersistentView to save it's own snapshots, but, in our case, it isn't required.

This PersistentView is sharded in the same way the PersistentActor is:

    val idExtractor: ShardRegion.IdExtractor = {
        case m : AuctionEvt => (m.auctionId,m)
        case m : BidQuery => (m.auctionId,m)
    }

    val shardResolver: ShardRegion.ShardResolver = {
        case m: AuctionEvt => (math.abs(m.auctionId.hashCode) % 100).toString
        case m: BidQuery => (math.abs(m.auctionId.hashCode) % 100).toString
    }

One could have used a different shard strategy here, but a consequence of the above strategy is that the Query Path will
            reside in the same Shard Region as the command path, reducing latency of the Update() message from Command to Query.

The PersistentView maintains the following model in memory:

    final case class BidState(auctionId:String,
                             start:Long,
                             end:Long,
                             product:Double,
                             acceptedBids:List[Bid],
                             rejectedBids:List[Bid],
                             closed:Boolean)

This model is sufficient to satisfy both queries: Winning Bid, and  Bid History:

      def auctionInProgress(currentState:BidState, prodId:String):Receive = {

        case  GetBidHistoryQuery(auctionId) =>  sender ! BidHistoryResponse(auctionId,currentState.acceptedBids)

        case  WinningBidPriceQuery(auctionId) =>
            currentState.acceptedBids.headOption.fold(
            sender ! WinningBidPriceResponse(auctionId,currentState.product))(b =>
            sender ! WinningBidPriceResponse(auctionId,b.price))
              ....
      }
