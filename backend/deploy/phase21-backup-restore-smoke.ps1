param(
    [Parameter(Mandatory)][string]$SourceDatabase,
    [Parameter(Mandatory)][string]$RestoreDatabase,
    [string]$HostName = "127.0.0.1",
    [string]$UserName = "root",
    [string]$MySqlBin = "C:\Program Files\MySQL\MySQL Server 8.0\bin"
)

$ErrorActionPreference = "Stop"
if ($RestoreDatabase -notmatch "_restore_smoke$") { throw "RestoreDatabase must end with _restore_smoke" }
if ($SourceDatabase -eq $RestoreDatabase) { throw "Source and restore databases must differ" }
if ([string]::IsNullOrWhiteSpace($env:DB_PASSWORD)) { throw "DB_PASSWORD is required" }
$env:MYSQL_PWD = $env:DB_PASSWORD
$dump = Join-Path ([IO.Path]::GetTempPath()) ("mediassist-restore-" + [guid]::NewGuid() + ".sql")
$mysql = Join-Path $MySqlBin "mysql.exe"
$mysqldump = Join-Path $MySqlBin "mysqldump.exe"
try {
    & $mysqldump "--user=$UserName" "--host=$HostName" --single-transaction --routines --triggers "--result-file=$dump" $SourceDatabase
    if ($LASTEXITCODE -ne 0) { throw "Backup failed" }
    & $mysql "--user=$UserName" "--host=$HostName" --execute "DROP DATABASE IF EXISTS ``$RestoreDatabase``; CREATE DATABASE ``$RestoreDatabase`` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
    $portableDump = $dump.Replace('\','/')
    & $mysql "--user=$UserName" "--host=$HostName" $RestoreDatabase --execute "SOURCE $portableDump;"
    if ($LASTEXITCODE -ne 0) { throw "Restore failed" }
    & $mysql "--user=$UserName" "--host=$HostName" --batch --skip-column-names --execute "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$SourceDatabase'; SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$RestoreDatabase'; SELECT MAX(CAST(version AS UNSIGNED)) FROM ``$RestoreDatabase``.flyway_schema_history WHERE success=1;"
} finally {
    if (Test-Path -LiteralPath $dump) { Remove-Item -LiteralPath $dump -Force }
    & $mysql "--user=$UserName" "--host=$HostName" --execute "DROP DATABASE IF EXISTS ``$RestoreDatabase``;"
}
