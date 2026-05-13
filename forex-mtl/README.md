# Forex Proxy

Local proxy service for exchange rates backed by Paidy's One-Frame service.

## Run

Start One-Frame on port 8080:

```sh
docker run -p 8080:8080 paidyinc/one-frame
```

Start this service on a different port:

```sh
sbt -J-Dapp.http.port=8081 run
```

Request a rate:

```sh
curl "http://localhost:8081/rates?from=USD&to=JPY"
```

## Test

```sh
sbt test
```

## Design

The service does not call One-Frame for every incoming request. On a cache miss or stale rate, it refreshes every supported directional currency pair in one batched One-Frame request, stores the result in memory, and serves later requests from that cache while rates are fresh.

Supported currencies are `AUD`, `CAD`, `CHF`, `EUR`, `GBP`, `NZD`, `JPY`, `SGD`, and `USD`. Same-currency pairs such as `USDUSD` are unsupported.

## Quota Math

There are 9 supported currencies, so there are `9 * 8 = 72` directional pairs.

Refreshing all pairs once every 5 minutes uses:

```text
12 refreshes/hour * 24 hours = 288 One-Frame requests/day
```

That stays below the One-Frame token limit of 1,000 requests/day while allowing 10,000+ local requests/day to be served mostly from memory.

## Assumptions

- Direction matters: `USDJPY` and `JPYUSD` are different rates.
- One-Frame's `time_stamp` is used to decide whether a rate is fresh.
- Rates older than the configured cache TTL are not served.
- If One-Frame fails and no fresh cached rate is available, the service returns an error instead of stale data.
- The default One-Frame token and local URL are configured in `src/main/resources/application.conf`.

## Known Limitations

- The cache is in-memory, so every service instance has its own cache.
- In a multi-instance production deployment, refreshes would need shared coordination, for example Redis, a scheduled refresher, or leader election.
- The cache is empty on restart.
- Retry behavior is intentionally minimal to avoid burning the One-Frame daily quota.

