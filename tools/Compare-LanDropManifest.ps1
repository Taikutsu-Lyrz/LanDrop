[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$ReferenceManifest,

    [Parameter(Mandatory = $true)]
    [string]$CandidateManifest,

    [string]$OutputPath,

    [switch]$IgnoreTimestamps
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function New-EntryMap {
    param([object[]]$Entries)

    $map = @{}
    foreach ($entry in $Entries) {
        $key = "{0}|{1}" -f $entry.type, $entry.relativePath
        $map[$key] = $entry
    }
    return $map
}

function Write-ReportLine {
    param(
        [System.Collections.Generic.List[string]]$Lines,
        [string]$Text
    )

    $Lines.Add($Text) | Out-Null
    Write-Host $Text
}

$referencePath = [System.IO.Path]::GetFullPath($ReferenceManifest)
$candidatePath = [System.IO.Path]::GetFullPath($CandidateManifest)

if (-not (Test-Path -LiteralPath $referencePath -PathType Leaf)) {
    throw "Reference manifest not found: $referencePath"
}

if (-not (Test-Path -LiteralPath $candidatePath -PathType Leaf)) {
    throw "Candidate manifest not found: $candidatePath"
}

$reference = Get-Content -LiteralPath $referencePath -Raw | ConvertFrom-Json
$candidate = Get-Content -LiteralPath $candidatePath -Raw | ConvertFrom-Json

$referenceMap = New-EntryMap -Entries $reference.entries
$candidateMap = New-EntryMap -Entries $candidate.entries

$missing = New-Object System.Collections.Generic.List[string]
$extra = New-Object System.Collections.Generic.List[string]
$mismatched = New-Object System.Collections.Generic.List[string]

foreach ($key in $referenceMap.Keys | Sort-Object) {
    if (-not $candidateMap.ContainsKey($key)) {
        $missing.Add($key) | Out-Null
        continue
    }

    $left = $referenceMap[$key]
    $right = $candidateMap[$key]

    if ($left.type -eq "file") {
        if ([long]$left.length -ne [long]$right.length) {
            $mismatched.Add("{0} length differs ({1} vs {2})" -f $left.relativePath, $left.length, $right.length) | Out-Null
            continue
        }

        if ($left.sha256 -ne $right.sha256) {
            $mismatched.Add("{0} sha256 differs" -f $left.relativePath) | Out-Null
            continue
        }
    }

    if (-not $IgnoreTimestamps -and $left.lastWriteUtc -ne $right.lastWriteUtc) {
        $mismatched.Add("{0} timestamp differs ({1} vs {2})" -f $left.relativePath, $left.lastWriteUtc, $right.lastWriteUtc) | Out-Null
    }
}

foreach ($key in $candidateMap.Keys | Sort-Object) {
    if (-not $referenceMap.ContainsKey($key)) {
        $extra.Add($key) | Out-Null
    }
}

$reportLines = New-Object System.Collections.Generic.List[string]
Write-ReportLine -Lines $reportLines -Text "LanDrop manifest comparison"
Write-ReportLine -Lines $reportLines -Text ("Reference : {0}" -f $referencePath)
Write-ReportLine -Lines $reportLines -Text ("Candidate : {0}" -f $candidatePath)
Write-ReportLine -Lines $reportLines -Text ("Missing   : {0}" -f $missing.Count)
Write-ReportLine -Lines $reportLines -Text ("Extra     : {0}" -f $extra.Count)
Write-ReportLine -Lines $reportLines -Text ("Mismatch  : {0}" -f $mismatched.Count)

if ($missing.Count -gt 0) {
    Write-ReportLine -Lines $reportLines -Text ""
    Write-ReportLine -Lines $reportLines -Text "Missing entries:"
    foreach ($entry in $missing | Select-Object -First 20) {
        Write-ReportLine -Lines $reportLines -Text ("  - {0}" -f $entry)
    }
}

if ($extra.Count -gt 0) {
    Write-ReportLine -Lines $reportLines -Text ""
    Write-ReportLine -Lines $reportLines -Text "Extra entries:"
    foreach ($entry in $extra | Select-Object -First 20) {
        Write-ReportLine -Lines $reportLines -Text ("  - {0}" -f $entry)
    }
}

if ($mismatched.Count -gt 0) {
    Write-ReportLine -Lines $reportLines -Text ""
    Write-ReportLine -Lines $reportLines -Text "Mismatched entries:"
    foreach ($entry in $mismatched | Select-Object -First 20) {
        Write-ReportLine -Lines $reportLines -Text ("  - {0}" -f $entry)
    }
}

if (-not [string]::IsNullOrWhiteSpace($OutputPath)) {
    $resolvedOutput = [System.IO.Path]::GetFullPath($OutputPath)
    $directory = Split-Path -Parent $resolvedOutput
    if (-not [string]::IsNullOrWhiteSpace($directory)) {
        [System.IO.Directory]::CreateDirectory($directory) | Out-Null
    }
    $reportLines -join [Environment]::NewLine | Set-Content -LiteralPath $resolvedOutput -Encoding UTF8
    Write-Host ("Report saved to {0}" -f $resolvedOutput)
}

if ($missing.Count -gt 0 -or $extra.Count -gt 0 -or $mismatched.Count -gt 0) {
    exit 1
}

Write-Host ""
Write-Host "Manifest comparison passed."
