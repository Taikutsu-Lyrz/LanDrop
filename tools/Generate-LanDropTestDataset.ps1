[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$OutputPath,

    [ValidateSet("smoke", "large", "soak")]
    [string]$Profile = "smoke",

    [double]$Scale = 1.0,

    [int]$Seed = 1337,

    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Format-Bytes {
    param([long]$Bytes)

    $units = @("B", "KiB", "MiB", "GiB", "TiB")
    $value = [double]$Bytes
    $unitIndex = 0

    while ($value -ge 1024 -and $unitIndex -lt ($units.Length - 1)) {
        $value = $value / 1024
        $unitIndex += 1
    }

    return "{0:N2} {1}" -f $value, $units[$unitIndex]
}

function Resolve-ScaledCount {
    param(
        [int]$BaseValue,
        [double]$ScaleValue,
        [int]$Minimum = 1
    )

    $scaled = [math]::Round($BaseValue * $ScaleValue)
    return [math]::Max($Minimum, [int]$scaled)
}

function Resolve-ScaledBytes {
    param(
        [long]$BaseValue,
        [double]$ScaleValue,
        [long]$Minimum = 1024
    )

    $scaled = [long][math]::Round($BaseValue * $ScaleValue)
    return [math]::Max($Minimum, $scaled)
}

function New-RandomFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,

        [Parameter(Mandatory = $true)]
        [long]$LengthBytes
    )

    $directory = Split-Path -Parent $Path
    if (-not [string]::IsNullOrWhiteSpace($directory)) {
        [System.IO.Directory]::CreateDirectory($directory) | Out-Null
    }

    $bufferSize = 1MB
    $buffer = New-Object byte[] $bufferSize
    $remaining = $LengthBytes
    $stream = [System.IO.File]::Open($Path, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()

    try {
        while ($remaining -gt 0) {
            $rng.GetBytes($buffer)
            $chunk = [int][math]::Min($buffer.Length, $remaining)
            $stream.Write($buffer, 0, $chunk)
            $remaining -= $chunk
        }
        $stream.Flush()
    }
    finally {
        $rng.Dispose()
        $stream.Dispose()
    }
}

function New-NestedPath {
    param(
        [System.Random]$Random,
        [int]$Depth
    )

    $parts = New-Object System.Collections.Generic.List[string]
    for ($level = 1; $level -le $Depth; $level++) {
        $parts.Add(("tier-{0:D2}-{1:D4}" -f $level, $Random.Next(1, 9999)))
    }
    return [System.IO.Path]::Combine($parts.ToArray())
}

$profileMap = @{
    smoke = @{
        HugeFilesBytes  = @(256MB)
        MediumFileCount = 12
        MediumFileBytes = 16MB
        SmallFileCount  = 250
        SmallFileBytes  = 64KB
        EmptyDirCount   = 4
        MaxDepth        = 3
    }
    large = @{
        HugeFilesBytes  = @(8GB, 4GB)
        MediumFileCount = 64
        MediumFileBytes = 32MB
        SmallFileCount  = 2500
        SmallFileBytes  = 128KB
        EmptyDirCount   = 16
        MaxDepth        = 6
    }
    soak = @{
        HugeFilesBytes  = @(20GB, 10GB)
        MediumFileCount = 128
        MediumFileBytes = 64MB
        SmallFileCount  = 10000
        SmallFileBytes  = 256KB
        EmptyDirCount   = 32
        MaxDepth        = 8
    }
}

if ($Scale -le 0) {
    throw "Scale must be greater than zero."
}

$resolvedOutput = [System.IO.Path]::GetFullPath($OutputPath)

if (Test-Path -LiteralPath $resolvedOutput) {
    if (-not $Force) {
        throw "Output path already exists. Re-run with -Force to overwrite: $resolvedOutput"
    }
    Remove-Item -LiteralPath $resolvedOutput -Recurse -Force
}

[System.IO.Directory]::CreateDirectory($resolvedOutput) | Out-Null

$random = [System.Random]::new($Seed)
$profileSpec = $profileMap[$Profile]
$createdFiles = 0
$createdDirectories = 0
$totalBytes = [long]0
$createdAtUtc = [DateTime]::UtcNow

$rootDirectories = @(
    [System.IO.Path]::Combine($resolvedOutput, "HugeFiles"),
    [System.IO.Path]::Combine($resolvedOutput, "Nested"),
    [System.IO.Path]::Combine($resolvedOutput, "ManySmallFiles"),
    [System.IO.Path]::Combine($resolvedOutput, "MixedContent")
)

foreach ($directory in $rootDirectories) {
    [System.IO.Directory]::CreateDirectory($directory) | Out-Null
    $createdDirectories += 1
}

for ($i = 0; $i -lt $profileSpec.EmptyDirCount; $i++) {
    $relative = [System.IO.Path]::Combine("Nested", (New-NestedPath -Random $random -Depth ($random.Next(1, $profileSpec.MaxDepth + 1))), "empty-{0:D3}" -f ($i + 1))
    $target = [System.IO.Path]::Combine($resolvedOutput, $relative)
    [System.IO.Directory]::CreateDirectory($target) | Out-Null
    $createdDirectories += 1
}

for ($index = 0; $index -lt $profileSpec.HugeFilesBytes.Count; $index++) {
    $sizeBytes = Resolve-ScaledBytes -BaseValue ([long]$profileSpec.HugeFilesBytes[$index]) -ScaleValue $Scale -Minimum 1MB
    $targetPath = [System.IO.Path]::Combine($resolvedOutput, "HugeFiles", ("huge-{0:D2}.bin" -f ($index + 1)))
    Write-Host ("Creating huge file {0} ({1})" -f $targetPath, (Format-Bytes -Bytes $sizeBytes))
    New-RandomFile -Path $targetPath -LengthBytes $sizeBytes
    $createdFiles += 1
    $totalBytes += $sizeBytes
}

$mediumCount = Resolve-ScaledCount -BaseValue $profileSpec.MediumFileCount -ScaleValue $Scale
$mediumBytes = Resolve-ScaledBytes -BaseValue ([long]$profileSpec.MediumFileBytes) -ScaleValue $Scale -Minimum 1MB

for ($index = 0; $index -lt $mediumCount; $index++) {
    $depth = $random.Next(1, $profileSpec.MaxDepth + 1)
    $relativeDirectory = [System.IO.Path]::Combine("MixedContent", (New-NestedPath -Random $random -Depth $depth))
    $absoluteDirectory = [System.IO.Path]::Combine($resolvedOutput, $relativeDirectory)
    [System.IO.Directory]::CreateDirectory($absoluteDirectory) | Out-Null
    $createdDirectories += 1

    $targetPath = [System.IO.Path]::Combine($absoluteDirectory, ("medium-{0:D4}.dat" -f ($index + 1)))
    New-RandomFile -Path $targetPath -LengthBytes $mediumBytes
    $createdFiles += 1
    $totalBytes += $mediumBytes
}

$smallCount = Resolve-ScaledCount -BaseValue $profileSpec.SmallFileCount -ScaleValue $Scale
$smallBytes = Resolve-ScaledBytes -BaseValue ([long]$profileSpec.SmallFileBytes) -ScaleValue $Scale -Minimum 4KB

for ($index = 0; $index -lt $smallCount; $index++) {
    $bucket = "bucket-{0:D3}" -f ($index % 64)
    $nestedPath = New-NestedPath -Random $random -Depth ($random.Next(1, [math]::Min(4, $profileSpec.MaxDepth) + 1))
    $absoluteDirectory = [System.IO.Path]::Combine($resolvedOutput, "ManySmallFiles", $bucket, $nestedPath)
    [System.IO.Directory]::CreateDirectory($absoluteDirectory) | Out-Null
    $createdDirectories += 1

    $targetPath = [System.IO.Path]::Combine($absoluteDirectory, ("small-{0:D6}.bin" -f ($index + 1)))
    New-RandomFile -Path $targetPath -LengthBytes $smallBytes
    $createdFiles += 1
    $totalBytes += $smallBytes
}

$metadata = [ordered]@{
    profile            = $Profile
    scale              = $Scale
    seed               = $Seed
    outputPath         = $resolvedOutput
    generatedAtUtc     = $createdAtUtc.ToString("o")
    fileCount          = $createdFiles
    directoryCount     = $createdDirectories
    totalBytes         = $totalBytes
    totalBytesReadable = Format-Bytes -Bytes $totalBytes
}

$metadataPath = [System.IO.Path]::Combine($resolvedOutput, "dataset-metadata.json")
$metadata | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $metadataPath -Encoding UTF8

Write-Host ""
Write-Host "LanDrop dataset generation complete."
Write-Host ("Profile      : {0}" -f $Profile)
Write-Host ("Scale        : {0}" -f $Scale)
Write-Host ("Output       : {0}" -f $resolvedOutput)
Write-Host ("Files        : {0}" -f $createdFiles)
Write-Host ("Directories  : {0}" -f $createdDirectories)
Write-Host ("Total size   : {0}" -f (Format-Bytes -Bytes $totalBytes))
Write-Host ("Metadata     : {0}" -f $metadataPath)
