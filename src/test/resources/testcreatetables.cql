CREATE TABLE IF NOT EXISTS products (
  id bigint PRIMARY KEY,
  name text, 
  description text
) WITH comment='auction products';

CREATE TABLE IF NOT EXISTS auctions (
  id text,
  prodid text,
  start timestamp, 
  end timestamp,
  initialprice double,
  PRIMARY KEY (id)
) WITH comment='auctions';

CREATE TABLE IF NOT EXISTS auctionbids (
  aid text,
  bprice double,
  btime timestamp,
  bbuyer text,
  PRIMARY KEY ((aid), bprice, bbuyer, btime)
) WITH comment='auctions with bids'
	AND CLUSTERING ORDER BY (bprice DESC)
	AND caching = '{"keys":"ALL", "rows_per_partition":"1"}';
