# akka-dddd-template
Akka DDDD template using CQRS/ES with a Distributed Domain



This application demonstrates the use of Akka Persistence to implement CQRS/ES.

The example domain is an online Auction.

Auction creation and subsequent bids will be processed by the PersistentActor **BidProcessor**

Queries about the current winning bid etc. are processed by the PersistentView **BidView**

The backing datastore is Cassandra. You have have this installed to run this application.

The Persistence Actors exist in an Akka Cluster, and are sharded according to auctionId.



####There is a Spray microservice accepting http requests and forwarding to cluster:

Create Auction:

POST  http://localhost:8080/startAuction

`{"auctionId":"123",
 "start":"2015-01-20-16:25",
 "end":"2015-05-20-16:35",
 "initialPrice" : 2,
 "prodId" : "3"}`


Place Bid:

POST http://localhost:8080/bid

`{"auctionId":"123",
 "buyer":"dave",
 "bidPrice":6}`


Get Winning Bid:

GET http://localhost:8080/winningBid/123


Get Bid History:

http://localhost:8080/bidHistory/123







####To start the cluster nodes (locally):

`sbt 'runMain com.boldradius.cqrs.ClusterNodeApp 127.0.0.1 2551'`

`sbt 'runMain com.boldradius.cqrs.ClusterNodeApp 127.0.0.1 2552'`





####To start the http node (Spray service):
`sbt 'runMain com.boldradius.cqrs.HttpApp 127.0.0.1 8080 127.0.0.1 0'`


The args are documented in the corresponding files:

**ClusterNodeApp.scala**

**HttpApp.scala**


In order to deploy to cloud (this app was tested on AWS), edit the application.conf accordingly

####Scala Version:  2.11.6

####Spray Version:  1.3.1

####Akka Version:   2.3.9

















