[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$RootPath,

    [Parameter(Mandatory = $true)]
    [string]$OutputPath,

    [switch]$SkipDirectoryEntries
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-RelativeUnixPath {
    param(
        [string]$BasePath,
        [string]$FullPath
    )

    $normalizedBase = $BasePath.TrimEnd("\") + "\"
    $baseUri = [System.Uri]::new($normalizedBase)
    $fullUri = [System.Uri]::new($FullPath)
    $relative = $baseUri.MakeRelativeUri($fullUri).ToString()
    return [System.Uri]::UnescapeDataString($relative).Replace("\", "/")
}

$resolvedRoot = [System.IO.Path]::GetFullPath($RootPath)
if (-not (Test-Path -LiteralPath $resolvedRoot -PathType Container)) {
    throw "RootPath does not exist or is not a directory: $resolvedRoot"
}

$resolvedOutput = [System.IO.Path]::GetFullPath($OutputPath)
$outputDirectory = Split-Path -Parent $resolvedOutput
if (-not [string]::IsNullOrWhiteSpace($outputDirectory)) {
    [System.IO.Directory]::CreateDirectory($outputDirectory) | Out-Null
}

$entries = New-Object System.Collections.Generic.List[object]
$fileCount = 0
$directoryCount = 0
$totalBytes = [long]0

if (-not $SkipDirectoryEntries) {
    foreach ($directory in Get-ChildItem -LiteralPath $resolvedRoot -Directory -Recurse -Force | Sort-Object FullName) {
        $entries.Add([ordered]@{
            type         = "directory"
            relativePath = Get-RelativeUnixPath -BasePath $resolvedRoot -FullPath $directory.FullName
            length       = 0
            lastWriteUtc = $directory.LastWriteTimeUtc.ToString("o")
            sha256       = $null
        })
        $directoryCount += 1
    }
}

foreach ($file in Get-ChildItem -LiteralPath $resolvedRoot -File -Recurse -Force | Sort-Object FullName) {
    $hash = Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256
    $entries.Add([ordered]@{
        type         = "file"
        relativePath = Get-RelativeUnixPath -BasePath $resolvedRoot -FullPath $file.FullName
        length       = [long]$file.Length
        lastWriteUtc = $file.LastWriteTimeUtc.ToString("o")
        sha256       = $hash.Hash.ToLowerInvariant()
    })
    $fileCount += 1
    $totalBytes += [long]$file.Length
}

$manifest = [ordered]@{
    rootPath       = $resolvedRoot
    generatedAtUtc = [DateTime]::UtcNow.ToString("o")
    fileCount      = $fileCount
    directoryCount = $directoryCount
    totalBytes     = $totalBytes
    entries        = $entries
}

$manifest | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $resolvedOutput -Encoding UTF8

Write-Host ("Manifest written to {0}" -f $resolvedOutput)
Write-Host ("Files       : {0}" -f $fileCount)
Write-Host ("Directories : {0}" -f $directoryCount)
Write-Host ("Total bytes : {0}" -f $totalBytes)
