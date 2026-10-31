param([switch]$WithRuntime)
$ErrorActionPreference = 'Stop'

$maven = Get-Command mvn -ErrorAction SilentlyContinue
if (-not $maven) {
    $maven = Get-ChildItem "$env:USERPROFILE\.m2\wrapper\dists" -Filter mvn.cmd -File -Recurse -ErrorAction SilentlyContinue |
        Select-Object -First 1
}
if (-not $maven) { throw 'Maven was not found on PATH or under .m2/wrapper/dists.' }
$mavenPath = if ($maven.Source) { $maven.Source } else { $maven.FullName }

if ($WithRuntime) {
    & $mavenPath verify
} else {
    & $mavenPath '-Dtest=MqttBridgeTest,RobotRealtimeSnapshotServiceTest,FacilityMapperCoordinatesTest' test
}
exit $LASTEXITCODE
