param(
  [string]$Uri = "http://localhost:8081/rates?from=USD&to=JPY",
  [int]$Jobs = 100,
  [int]$RequestsPerJob = 100
)

$totalRequests = $Jobs * $RequestsPerJob

Write-Host "Warming cache with $Uri ..."
Invoke-WebRequest -UseBasicParsing $Uri | Out-Null

Write-Host "Starting smoke load test..."
Write-Host "Requests: $totalRequests"
Write-Host "Concurrency: $Jobs PowerShell jobs"
Write-Host "Watch the Forex app logs for: Refreshing all rates from One-Frame"

$startedAt = Get-Date

$runningJobs = 1..$Jobs | ForEach-Object {
  Start-Job -ScriptBlock {
    param($Uri, $RequestsPerJob)

    $success = 0
    $failure = 0

    1..$RequestsPerJob | ForEach-Object {
      try {
        $response = Invoke-WebRequest -UseBasicParsing $Uri
        if ($response.StatusCode -eq 200) {
          $success += 1
        } else {
          $failure += 1
        }
      } catch {
        $failure += 1
      }
    }

    [PSCustomObject]@{
      Success = $success
      Failure = $failure
    }
  } -ArgumentList $Uri, $RequestsPerJob
}

$runningJobs | Wait-Job | Out-Null
$results = $runningJobs | Receive-Job
$runningJobs | Remove-Job

$elapsed = (Get-Date) - $startedAt
$successes = ($results | Measure-Object -Property Success -Sum).Sum
$failures = ($results | Measure-Object -Property Failure -Sum).Sum

Write-Host "Elapsed: $($elapsed.TotalSeconds) seconds"
Write-Host "Successful responses: $successes"
Write-Host "Failed responses: $failures"
Write-Host "Approx requests/sec, including PowerShell job overhead: $($totalRequests / $elapsed.TotalSeconds)"

