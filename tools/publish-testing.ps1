# Run from the root of your existing clone. The package folder contains this
# script, changes.bundle, package-manifest.json, release-notes.md and release-assets.
# Compatible with Windows PowerShell 5.1. Only testing and the new release tag
# are pushed; the working tree and checked-out branch are left in place.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Folder,
    [string]$Repo = 'Hexclyde/bitchord-morphe-patches'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

function Step([string]$Text) { Write-Host "`n==> $Text" -ForegroundColor Cyan }

function Invoke-Git {
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & git @args; $code = $LASTEXITCODE }
    finally { $ErrorActionPreference = $saved }
    if ($code -ne 0) { throw "git failed (exit $code). Resolve the reported error before retrying." }
}

function Invoke-Gh {
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & gh @args; $code = $LASTEXITCODE }
    finally { $ErrorActionPreference = $saved }
    if ($code -ne 0) { throw "GitHub CLI failed (exit $code). Inspect the release before retrying." }
}

function Try-Native {
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $args[0] @($args | Select-Object -Skip 1) *> $null; return ($LASTEXITCODE -eq 0) }
    catch { return $false }
    finally { $ErrorActionPreference = $saved }
}

function Assert-Hash([string]$Path, [string]$Expected) {
    if ($Expected -notmatch '^[0-9a-f]{64}$') { throw "Invalid expected checksum for $Path." }
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Missing $Path. Extract the whole ZIP into -Folder." }
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $Expected) { throw "Checksum mismatch for $Path. Extract the ZIP again." }
}

Step 'Checking prerequisites and package'
foreach ($command in @('git', 'gh')) {
    if (-not (Get-Command $command -CommandType Application -ErrorAction SilentlyContinue)) {
        throw "Install $command first. GitHub CLI also requires 'gh auth login'."
    }
}
$Folder = (Resolve-Path -LiteralPath $Folder).Path
$ManifestPath = Join-Path $Folder 'package-manifest.json'
if (-not (Test-Path -LiteralPath $ManifestPath -PathType Leaf)) { throw 'Extract the whole ZIP into -Folder.' }
$manifest = Get-Content -LiteralPath $ManifestPath -Raw | ConvertFrom-Json
if ($Repo -cne 'Hexclyde/bitchord-morphe-patches' -or $manifest.repository -cne $Repo) {
    throw 'This package is for Hexclyde/bitchord-morphe-patches.'
}
if ($manifest.version -cne '1.1.0-beta.14') { throw 'This script requires the 1.1.0-beta.14 publishing package.' }
$Version = $manifest.version
$Tag = "v$Version"
$Mpp = "bitchord-patches-$Version.mpp"
$ExpectedTip = [string]$manifest.commit
$Baseline = [string]$manifest.baseline_commit
if ($ExpectedTip -notmatch '^[0-9a-f]{40}$' -or $Baseline -notmatch '^[0-9a-f]{40}$') { throw 'Invalid package commit.' }
$Bundle = Join-Path $Folder 'changes.bundle'
$Notes = Join-Path $Folder 'release-notes.md'
$Assets = Join-Path $Folder 'release-assets'
Assert-Hash $Bundle $manifest.bundle_sha256
Assert-Hash $Notes $manifest.notes_sha256
Assert-Hash (Join-Path $Assets 'SHA256SUMS') $manifest.checksums_sha256
$required = @($Mpp, 'patches-bundle.json', 'patches-list.json')
$seen = @{}
foreach ($line in Get-Content -LiteralPath (Join-Path $Assets 'SHA256SUMS')) {
    if ($line -notmatch '^([0-9a-f]{64})  ([A-Za-z0-9][A-Za-z0-9._-]*)$') { throw 'Invalid SHA256SUMS entry.' }
    $expected = $Matches[1]
    $name = $Matches[2]
    if ($required -cnotcontains $name -or $seen.ContainsKey($name)) { throw "Unexpected checksum entry: $name." }
    Assert-Hash (Join-Path $Assets $name) $expected
    $seen[$name] = $expected
    Write-Host "OK  $name"
}
if ($seen.Count -ne $required.Count) { throw 'SHA256SUMS must cover all three release assets.' }
foreach ($name in @('patches-bundle.json', 'patches-list.json')) {
    $metadata = Get-Content -LiteralPath (Join-Path $Assets $name) -Raw | ConvertFrom-Json
    if ($metadata.version -cne $Version) { throw "Unexpected version in $name." }
}

$root = (Invoke-Git rev-parse --show-toplevel).Trim()
if ([IO.Path]::GetFullPath($root).TrimEnd([char[]]'\/') -ne
    [IO.Path]::GetFullPath((Get-Location).Path).TrimEnd([char[]]'\/')) {
    throw 'Run this script from the root of your existing repository clone.'
}
$remoteUrls = @(Invoke-Git remote get-url --all origin)
if ($remoteUrls.Count -ne 1) { throw 'origin must have exactly one fetch URL.' }
$remote = $remoteUrls[0].Trim().TrimEnd('/')
$allowed = @("https://github.com/$Repo", "https://github.com/$Repo.git",
    "git@github.com:$Repo", "git@github.com:$Repo.git",
    "ssh://git@github.com/$Repo", "ssh://git@github.com/$Repo.git")
if ($allowed -notcontains $remote) { throw 'origin must be the Hexclyde/bitchord-morphe-patches GitHub repository.' }
$pushUrls = @(Invoke-Git remote get-url --push --all origin)
if ($pushUrls.Count -ne 1) { throw 'origin must have exactly one push URL.' }
$pushRemote = $pushUrls[0].Trim().TrimEnd('/')
if ($allowed -notcontains $pushRemote) { throw 'origin push URL must point to the same GitHub repository.' }
if (@(Invoke-Git status --porcelain --untracked-files=all).Count -ne 0) {
    throw 'Save, commit or stash your local changes (including untracked files) before running this script.'
}
if (-not (Try-Native gh auth status)) { throw "Run 'gh auth login' first." }
# An API failure must stop publishing, rather than being mistaken for an absent release.
$releases = (Invoke-Gh api --paginate "repos/$Repo/releases?per_page=100" --jq '.[].tag_name')
if (@($releases) -contains $Tag) { throw "Release $Tag already exists. Inspect it before retrying." }

Step 'Fetching the latest repository state'
Invoke-Git fetch --prune origin
if (-not (Try-Native git rev-parse --verify --quiet refs/remotes/origin/main)) {
    throw 'origin/main is missing. Check the existing clone and its fetch configuration.'
}
if (@(Invoke-Git ls-remote --tags origin "refs/tags/$Tag").Count -ne 0) {
    throw "Remote tag $Tag already exists. Inspect it before retrying."
}

$IncomingRef = 'refs/bitchord-incoming/' + [Guid]::NewGuid().ToString('N')
$Imported = $false
$DownloadDirectory = $null
try {
    Step 'Importing and checking the packaged source'
    Invoke-Git bundle verify $Bundle
    Invoke-Git fetch --no-tags $Bundle "refs/heads/fix-download-player-beta14:$IncomingRef"
    $Imported = $true
    $tip = (Invoke-Git rev-parse $IncomingRef).Trim()
    if ($tip -cne $ExpectedTip) { throw 'The bundle commit does not match this package.' }
    foreach ($base in @($Baseline, 'refs/remotes/origin/main')) {
        if (-not (Try-Native git merge-base --is-ancestor $base $IncomingRef)) {
            throw 'The bundle is not based on the expected repository history. Ask for an updated package.'
        }
    }
    if ((Try-Native git rev-parse --verify --quiet refs/remotes/origin/testing) -and
        -not (Try-Native git merge-base --is-ancestor refs/remotes/origin/testing $IncomingRef)) {
        throw 'testing contains changes outside this package. Ask for an updated package; no remote changes were made.'
    }
    $provenance = (Invoke-Git show "${IncomingRef}:PROVENANCE.json") -join "`n" | ConvertFrom-Json
    if ($provenance.patch_bundle_version -cne $Version -or $provenance.patch_bundle_sha256 -cne $seen[$Mpp]) {
        throw 'The source provenance and packaged MPP do not match.'
    }
    foreach ($name in @('patches-bundle.json', 'patches-list.json')) {
        $committed = (Invoke-Git show "${IncomingRef}:$name") -join "`n"
        $asset = (Get-Content -LiteralPath (Join-Path $Assets $name)) -join "`n"
        if ($committed -cne $asset) { throw "$name differs between the bundle and release assets." }
    }
    Invoke-Git --no-pager log --oneline "refs/remotes/origin/main..$IncomingRef"

    Write-Host ''
    $answer = Read-Host "Push to testing and publish $Tag as a pre-release? (y/n)"
    if ($answer -ine 'y') { Write-Host 'Stopped. No remote changes were made.'; return }

    Step 'Pushing testing and the release tag'
    # GitHub supports an atomic push. Neither ref is forced; a concurrent update
    # that prevents a fast-forward or creates this tag makes the push fail.
    Invoke-Git push --atomic origin "${IncomingRef}:refs/heads/testing" "${IncomingRef}:refs/tags/$Tag"

    Step "Publishing $Tag"
    Invoke-Gh release create $Tag `
        (Join-Path $Assets $Mpp) `
        (Join-Path $Assets 'patches-bundle.json') `
        (Join-Path $Assets 'patches-list.json') `
        (Join-Path $Assets 'SHA256SUMS') `
        --repo $Repo --verify-tag --prerelease `
        --title $Tag --notes-file $Notes

    Step 'Checking the published download'
    $DownloadDirectory = Join-Path ([IO.Path]::GetTempPath()) ('bitchord-verify-' + [Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $DownloadDirectory | Out-Null
    Invoke-Gh release download $Tag --repo $Repo --pattern $Mpp --dir $DownloadDirectory
    Assert-Hash (Join-Path $DownloadDirectory $Mpp) $seen[$Mpp]
    Write-Host 'Published MPP matches the packaged build.'
    Step 'Done'
    Write-Host "$Tag is published as a pre-release. testing points to $ExpectedTip."
    Write-Host 'Your checked-out branch and working files were left in place.'
    Write-Host "Morphe testing source: https://raw.githubusercontent.com/$Repo/refs/heads/testing/patches-bundle.json"
    Write-Host 'Check the repository Actions tab for the remote build checks.'
}
finally {
    if ($Imported) {
        # Delete only the unique temporary ref created by this invocation.
        if (-not (Try-Native git update-ref -d $IncomingRef $ExpectedTip)) {
            Write-Warning "Could not remove temporary ref $IncomingRef."
        }
    }
    if ($null -ne $DownloadDirectory -and (Test-Path -LiteralPath $DownloadDirectory)) {
        Remove-Item -LiteralPath $DownloadDirectory -Recurse -Force
    }
}
