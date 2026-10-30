# Import the scoped compatibility patch into the current clean checkout.
# Windows PowerShell 5.1 or newer. No commits, remote writes or branch switches.
[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$Folder)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

function Invoke-Git {
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & git @args; $code = $LASTEXITCODE }
    finally { $ErrorActionPreference = $saved }
    if ($code -ne 0) { throw "git failed (exit $code). No commit or remote update was made." }
}

function Test-Git {
    $saved = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & git @args *> $null; return ($LASTEXITCODE -eq 0) }
    catch { return $false }
    finally { $ErrorActionPreference = $saved }
}

function Assert-Clean {
    if (@(Invoke-Git status --porcelain --untracked-files=all).Count -ne 0) {
        throw 'Save, commit or stash local changes, including untracked files, before importing.'
    }
}

function Assert-ScopedPath([string]$Path) {
    $exact = @(
        'test.py', 'resources/soulseek/compatibility-1.9.json',
        'checks/VerifyPatchUiApk.java', 'checks/VerifySoulseekApk.java',
        'checks/VerifySoulseekHostAbi.java',
        'patches/src/main/kotlin/app/bitchord/patches/PatchesUiPatch.kt',
        'patches/src/main/kotlin/app/bitchord/patches/SoulseekPatch.kt',
        'extensions/soulseek/src/main/java/app/bitchord/soulseek/SoulseekDownloadRoute.java',
        'extensions/soulseek/src/main/java/app/bitchord/soulseek/SoulseekSelectedDownloads.java',
        'extensions/soulseek/src/main/java/app/bitchord/soulseek/DebridSetupActivity.java',
        'extensions/soulseek/src/main/java/app/bitchord/soulseek/DownloadStyle.java',
        'tests/selected-downloads/app/bitchord/soulseek/SoulseekSelectedDownloadsTest.java',
        '.gitignore', 'ui-tests/run.py',
        'ui-tests/src/test/java/app/bitchord/soulseek/DebridSetupScreenTest.java',
        'ui-tests/src/test/java/app/bitchord/soulseek/DebridUiFixtures.java',
        'tests/download-route/app/bitchord/soulseek/SoulseekDownloadRouteTest.java',
        'tests/download-route/run.py', 'tools/validate_soulseek_19.py',
        'tools/import-soulseek-1.9.ps1', 'docs/SOULSEEK-BITCHORD-1.9.md'
    )
    $fixture = $Path -cmatch '^tests/download-route-v19/[A-Za-z0-9_./-]+\.java$'
    if (@($Path.Split('/') | Where-Object { $_ -eq '.' -or $_ -eq '..' }).Count -ne 0 -or
        ($exact -cnotcontains $Path -and -not $fixture)) {
        throw "The patch contains an unexpected file outside the reviewed scope: $Path"
    }
}

if (-not (Get-Command git -CommandType Application -ErrorAction SilentlyContinue)) {
    throw 'Install Git for Windows first.'
}
$Folder = (Resolve-Path -LiteralPath $Folder).Path
$manifestPath = Join-Path $Folder 'package-manifest.json'
$patch = Join-Path $Folder 'compatibility.patch'
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf) -or
    -not (Test-Path -LiteralPath $patch -PathType Leaf)) {
    throw 'Extract the complete compatibility ZIP into -Folder.'
}
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
foreach ($property in @('schema_version', 'package', 'version', 'repository',
                        'baseline_commit', 'patch_sha256', 'files')) {
    if ($manifest.PSObject.Properties.Name -cnotcontains $property) {
        throw "The package manifest is missing $property. Extract the ZIP again."
    }
}
if ($manifest.schema_version -ne 1 -or $manifest.package -cne 'bitchord-soulseek-1.9' -or
    $manifest.version -cne '1.1.0-beta.7-soulseek.1' -or
    $manifest.repository -cne 'Hexclyde/bitchord-morphe-patches' -or
    $manifest.baseline_commit -cne '93ec719c04a56205993b15e0adee52b45fdf13f2') {
    throw 'This script requires the scoped beta.7 Soulseek / BitChord 1.9 compatibility package.'
}
if ($manifest.patch_sha256 -cnotmatch '^[0-9a-f]{64}$' -or
    (Get-FileHash -LiteralPath $patch -Algorithm SHA256).Hash.ToLowerInvariant() -cne
        $manifest.patch_sha256) {
    throw 'compatibility.patch failed its SHA-256 check. Extract the ZIP again.'
}

$root = (Invoke-Git rev-parse --show-toplevel).Trim()
if ([IO.Path]::GetFullPath($root).TrimEnd([char[]]'\/') -ne
    [IO.Path]::GetFullPath((Get-Location).Path).TrimEnd([char[]]'\/')) {
    throw 'Run this script from the root of the existing patches repository.'
}
Assert-Clean
$startHead = (Invoke-Git rev-parse HEAD).Trim()
if (-not (Test-Git merge-base --is-ancestor $manifest.baseline_commit HEAD)) {
    throw 'Import the completed 1.1.0-beta.7 source first. This compatibility patch requires beta.7 or a later descendant.'
}
$files = @()
foreach ($line in @(Invoke-Git apply --numstat $patch)) {
    if ($line -cnotmatch '^\d+\t\d+\t(.+)$') {
        throw 'The compatibility patch must contain ordinary text file changes only.'
    }
    $path = $Matches[1]
    Assert-ScopedPath $path
    $files += $path
}
if ($files.Count -eq 0 -or $files.Count -gt 128 -or
    @($files | Sort-Object -Unique).Count -ne $files.Count) {
    throw 'The compatibility patch has an invalid or duplicate file list.'
}
$expectedFiles = @($manifest.files | Sort-Object)
$actualFiles = @($files | Sort-Object)
if (($expectedFiles -join "`n") -cne ($actualFiles -join "`n")) {
    throw 'The compatibility patch file list does not match its manifest.'
}

$temporary = Join-Path ([IO.Path]::GetTempPath()) ('bitchord-soulseek19-' + [Guid]::NewGuid().ToString('N'))
try {
    Write-Host 'Checking the three-way merge in a disposable local checkout...' -ForegroundColor Cyan
    $null = New-Item -ItemType Directory -Path $temporary
    $preview = Join-Path $temporary 'preview'
    $emptyHooks = Join-Path $temporary 'hooks'
    $null = New-Item -ItemType Directory -Path $emptyHooks
    Invoke-Git clone --shared --no-checkout --quiet --no-tags $root $preview
    Invoke-Git -C $preview -c "core.hooksPath=$emptyHooks" checkout --detach --quiet $startHead
    Invoke-Git -C $preview apply --3way --index $patch
    $changed = @(Invoke-Git -C $preview diff --cached --name-only)
    foreach ($path in $changed) { Assert-ScopedPath $path }
    if ((($changed | Sort-Object) -join "`n") -cne ($actualFiles -join "`n")) {
        throw 'The merge changed an unexpected file set. The current checkout has not been changed.'
    }
    Assert-Clean
    if ((Invoke-Git rev-parse HEAD).Trim() -cne $startHead) {
        throw 'The current branch changed during the preview. Run the importer again.'
    }
    Write-Host 'Applying the verified compatibility patch to the current checkout...' -ForegroundColor Cyan
    Invoke-Git apply --3way --index $patch
    Invoke-Git diff --cached --stat
}
finally {
    # Only this script's newly created disposable checkout is removed.
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Recurse -Force }
}

Write-Host "`nImported and staged. Review and commit when ready:" -ForegroundColor Green
Write-Host 'git diff --cached'
Write-Host 'git commit -m "Support Soulseek downloads on BitChord 1.9"'
Write-Host 'No commit, push, release or change to another branch was made.'
