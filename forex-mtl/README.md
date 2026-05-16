# Forex Proxy

Local proxy service for exchange rates backed by Paidy's One-Frame service.

## Run

Start One-Frame on port 8080:

```sh
docker run -p 8080:8080 paidyinc/one-frame
```

The default One-Frame token (`10dc303535874aeccc86a8251e6992f5`) is pre-configured.

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

The test suite runs in memory and does not require Docker or a running One-Frame container.

## Load Smoke Test

After starting One-Frame and this service, run:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\load-test.ps1
```

The script warms the cache once, then sends 10,000 requests to `GET /rates?from=USD&to=JPY` using 100 PowerShell jobs. It is intended as a local smoke test, not a precise latency benchmark.

To verify provider protection, watch the service logs for:

```text
Refreshing all rates from One-Frame
```

Expected behavior:

- cold cache: 1 refresh log line
- warm cache within the TTL window: 0 additional refresh log lines during the 10,000-request run

This confirms that local request volume is served from the cache rather than turning into one One-Frame request per local request.

Local smoke-test result:

```text
Requests: 10000
Elapsed reported by script: 9.97 seconds
Approx requests/sec reported by script: 1003
Observed One-Frame refresh logs during warm-cache run: 0 additional refreshes
```

The PowerShell script has noticeable job startup and scheduling overhead, so these numbers are used only as a correctness smoke test. The key result is that the 10,000 local requests did not produce additional One-Frame refreshes while the cache was warm.

## Design

The service does not call One-Frame for every incoming request. On a cache miss or stale rate, it refreshes every supported directional currency pair in one batched One-Frame request, stores the result in memory, and serves later requests from that cache while rates are fresh.

On first request after startup or restart, the cache is empty, so the service fetches all 72 supported pairs from One-Frame before responding. That first request may be slightly slower than later cache hits.

Supported currencies are `AUD`, `CAD`, `CHF`, `EUR`, `GBP`, `NZD`, `JPY`, `SGD`, and `USD`. Same-currency pairs such as `USDUSD` are unsupported and return `404 Not Found`.

Cache TTL, One-Frame base URL, and auth token are configurable in `src/main/resources/application.conf` or via JVM system properties.

## Code Structure

- `src/main/scala/forex/domain/`
  Domain types such as `Currency`, `Rate`, `Price`, and `Timestamp`. `Currency.fromString` handles safe parsing, and `Rate.Pair.all` defines all supported directional pairs.
- `src/main/scala/forex/services/rates/interpreters/OneFrameLive.scala`
  Small HTTP client for One-Frame. It builds the `/rates?pair=...` request, sends the token header, decodes the provider response, and maps provider failures.
- `src/main/scala/forex/services/rates/interpreters/OneFrameCached.scala`
  Cache-backed rates service. It checks freshness, refreshes all pairs in one batched One-Frame call, and uses a `Semaphore` to avoid duplicate refreshes under concurrent load.
- `src/main/scala/forex/programs/rates/`
  Application layer between HTTP and services. It keeps HTTP concerns out of the service implementation and maps service errors into program errors.
- `src/main/scala/forex/http/rates/`
  HTTP API layer. It parses query params, maps successful rates to JSON, and maps program errors to stable HTTP responses.
- `src/main/scala/forex/config/`
  Typed application configuration for HTTP, One-Frame, and cache TTL.
- `src/main/scala/forex/Main.scala` and `src/main/scala/forex/Module.scala`
  Runtime wiring. They build the http4s client, live cached rates service, program, and routes.

## Quota Math

There are 9 supported currencies, so there are `9 * 8 = 72` directional pairs.

Refreshing all pairs once every 5 minutes uses:

```text
12 refreshes/hour * 24 hours = 288 One-Frame requests/day
```

That stays below the One-Frame token limit of 1,000 requests/day while allowing 10,000+ local requests/day to be served mostly from memory.

## Error Responses

| Scenario | HTTP status | Example body |
| --- | --- | --- |
| Valid request | `200 OK` | `{"from":"USD","to":"JPY","price":123.45,"timestamp":"..."}` |
| Missing `from` or `to` query parameter | `400 Bad Request` | `{"error":"missing_query_params","message":"Both 'from' and 'to' query parameters are required"}` |
| Invalid currency | `400 Bad Request` | `{"error":"invalid_currency","message":"Unsupported currency"}` |
| Unsupported or same-currency pair | `404 Not Found` | `{"error":"unsupported_pair","message":"Unsupported currency pair"}` |
| One-Frame failure | `502 Bad Gateway` | `{"error":"provider_unavailable","message":"Unable to refresh rates from One-Frame"}` |
| No fresh rate available | `503 Service Unavailable` | `{"error":"no_fresh_rate","message":"No fresh rate available"}` |

## Compliance Checks

```sh
curl "http://localhost:8081/rates?from=USD&to=JPY" # 200 OK
curl "http://localhost:8081/rates?from=BTC&to=USD" # 400 Bad Request
curl "http://localhost:8081/rates?from=USD"        # 400 Bad Request
curl "http://localhost:8081/rates?from=USD&to=USD" # 404 Not Found
```

## Assumptions

- Direction matters: `USDJPY` and `JPYUSD` are different rates.
- Same-currency pairs are not exchange rates and are treated as unsupported.
- One-Frame's `time_stamp` is used to decide whether a rate is fresh.
- Rates older than the configured cache TTL are not served.
- If One-Frame fails and no fresh cached rate is available, the service returns an error instead of stale data. Serving stale rates risks mispricing in downstream financial calculations, so the service fails clearly rather than silently returning outdated data.
- The default One-Frame token and local URL are configured in `src/main/resources/application.conf`.

## Known Limitations

- The cache is in-memory, so every service instance has its own cache.
- In a multi-instance production deployment, refreshes would need shared coordination, for example Redis, a scheduled refresher, or leader election.
- The cache is empty on restart.
- Retry behavior is intentionally minimal to avoid burning the One-Frame daily quota.
- A production deployment would likely expose separate liveness and readiness endpoints. Readiness could include cache age, cached pair count, and last refresh status.
