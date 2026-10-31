param(
    [string]$BaseUri = "http://127.0.0.1:8080",
    [ValidateRange(1, 100000)][int]$Requests = 500,
    [ValidateRange(1, 500)][int]$Concurrency = 25
)

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Net.Http
$client = New-Object System.Net.Http.HttpClient
$watch = [System.Diagnostics.Stopwatch]::StartNew()
$succeeded = 0
$failed = 0
try {
    for ($offset = 0; $offset -lt $Requests; $offset += $Concurrency) {
        $batchSize = [Math]::Min($Concurrency, $Requests - $offset)
        $tasks = @(1..$batchSize | ForEach-Object { $client.GetAsync("$BaseUri/actuator/health") })
        [System.Threading.Tasks.Task]::WaitAll([System.Threading.Tasks.Task[]]$tasks)
        foreach ($task in $tasks) {
            if ([int]$task.Result.StatusCode -eq 200) { $succeeded++ } else { $failed++ }
            $task.Result.Dispose()
        }
    }
} finally {
    $watch.Stop()
    $client.Dispose()
}

$result = [pscustomobject]@{
    Requests = $Requests
    Concurrency = $Concurrency
    Succeeded = $succeeded
    Failed = $failed
    ElapsedMs = $watch.ElapsedMilliseconds
    RequestsPerSecond = [Math]::Round($Requests / $watch.Elapsed.TotalSeconds, 2)
}
$result
if ($failed -ne 0 -or $succeeded -ne $Requests) { throw "Load smoke failed" }
