<#
.SYNOPSIS
  Sync this fork with upstream Spotilol (origin = https://github.com/lyssadev/Spotilol).

.EXAMPLE
  .\scripts\sync-upstream.ps1            fetch + merge upstream into the current branch
  .\scripts\sync-upstream.ps1 -Build     run the debug build after merging/resolving
  .\scripts\sync-upstream.ps1 -Abort     abandon a conflicted merge

UPSTREAM RELEASES ARE HUGE "Bump to vX" COMMITS. Conflicts only happen where our
customizations overlap their changes. After `git merge` the script prints conflicts
and auto-resolves the safe ones (version-only hunks). The rest follows this playbook:

  app/build.gradle.kts      DO NOT take theirs wholesale - ours adds the Hebrew
                            values-iw sync task (syncIwLocaleRes). Resolve hunk by
                            hunk; versionCode/versionName = higher side wins
                            (script auto-resolves when hunks are version-only).
  settings.gradle.kts       keep rootProject.name = "Spotify" (ours), take additions
  res/values/strings.xml    branding lines (Spotilol -> Spotify): keep ours.
                            New upstream keys: take theirs, then add the Hebrew
                            translation to values-he/strings.xml (same key name).
  res/values*/themes.xml    keep Theme.Spotify (ours) unless upstream restyled
  UpdateChecker.kt          keep OWNER = "Katzover" / REPO = "Spotilol" (ours),
                            take upstream's logic changes
  AndroidManifest.xml       combine: ours has screenOrientation="portrait" and
                            @style/Theme.Spotify
  MainActivity / SplashActivity / SettingsScreen / SettingsDialog
                            take upstream features, keep our pieces:
                            LocaleHelper.wrap() in attachBaseContext, the update
                            confirmation dialog, restartApp() warm restart,
                            portrait lock, LocalizedResourcesContext
  webview/injections/*.kt   usually only the CREDIT comment conflicts: keep either
  README.md / .devin        keep our fork links + titles, take upstream content

After resolving everything: re-run this script (it finishes the merge) with -Build.
#>
param(
    [switch]$Abort,
    [switch]$Build
)

$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

if ($Abort) {
    git merge --abort
    if ($LASTEXITCODE -ne 0) { exit 1 }
    "Merge aborted."
    exit 0
}

function Invoke-Build {
    & .\gradlew.bat :app:assembleDebug --offline -q
    if ($LASTEXITCODE -ne 0) { Write-Error "BUILD FAILED"; exit 1 }
    "BUILD_OK"
}

function Get-ConflictHunks {
    param([string]$Text)
    return [regex]::Matches(
        $Text,
        "(?s)<<<<<<< [^\r\n]*\r?\n(?<ours>.*?)=======\r?\n(?<theirs>.*?)>>>>>>> [^\r\n]*\r?\n"
    )
}

# Auto-resolve a conflicted file whose hunks ONLY touch versionCode/versionName
# lines: the higher versionCode side wins, keeping every other hunk from both.
function Resolve-VersionOnlyFile {
    param([string]$Path)
    if (-not (Test-Path $Path)) { return $false }
    $text = Get-Content $Path -Raw
    $hunks = Get-ConflictHunks -Text $text
    if ($hunks.Count -eq 0) { return $false }

    $verLine = [regex]'^\s*(versionCode = \d+|versionName = "[^"]*")\s*$'
    $vcOurs = -1; $vcTheirs = -1
    foreach ($h in $hunks) {
        foreach ($side in @(@{n = 'ours'; v = $h.Groups['ours'].Value},
                            @{n = 'theirs'; v = $h.Groups['theirs'].Value})) {
            foreach ($line in ($side.v -split "`r?`n")) {
                if ($line.Trim() -eq '') { continue }
                if (-not $verLine.IsMatch($line)) { return $false }  # not version-only -> manual
            }
            $m = [regex]::Match($side.v, 'versionCode = (\d+)')
            if ($m.Success) {
                $vc = [int]$m.Groups[1].Value
                if ($side.n -eq 'ours') { $vcOurs = [Math]::Max($vcOurs, $vc) }
                else { $vcTheirs = [Math]::Max($vcTheirs, $vc) }
            }
        }
    }
    $takeOurs = $vcOurs -ge $vcTheirs
    $newText = [regex]::Replace($text, '(?s)<<<<<<< [^\r\n]*\r?\n(?<ours>.*?)=======\r?\n(?<theirs>.*?)>>>>>>> [^\r\n]*\r?\n', {
        param($m)
        if ($takeOurs) { $m.Groups['ours'].Value } else { $m.Groups['theirs'].Value }
    })
    [System.IO.File]::WriteAllText((Join-Path (Get-Location) $Path), $newText)
    git add -- $Path
    return $true
}

# --- merge already in progress: auto-resolve + report -------------------------
if (Test-Path ".git\MERGE_HEAD") {
    $pending = @(git diff --name-only --diff-filter=U)
    foreach ($f in $pending) {
        if (Resolve-VersionOnlyFile -Path $f) { "auto-resolved version conflict: $f" }
    }
    $pending = @(git diff --name-only --diff-filter=U)
    if ($pending.Count -eq 0) {
        git commit --no-edit
        if ($LASTEXITCODE -ne 0) { exit 1 }
        "Merge completed."
        if ($Build) { Invoke-Build }
        exit 0
    }
    "REMAINING CONFLICTS (resolve manually, then re-run this script):"
    $pending | ForEach-Object { "  $_" }
    if ($Build) { Invoke-Build }   # build works on partially staged trees only if clean; still useful after resolution
    exit 1
}

# --- fresh sync ---------------------------------------------------------------
$branch = git branch --show-current
if (-not $branch) { Write-Error "Not on a branch."; exit 1 }
"Branch: $branch  (upstream remote = origin = lyssadev/Spotilol)"

git fetch origin
if ($LASTEXITCODE -ne 0) { exit 1 }
$behind = [int](git rev-list --count "HEAD..origin/main")
if ($behind -eq 0) {
    "Already up to date with upstream (origin/main)."
    if ($Build) { Invoke-Build }
    exit 0
}
"Upstream has $behind new commit(s). Merging origin/main..."
git merge --no-ff -m "Sync upstream Spotilol" origin/main
if ($LASTEXITCODE -eq 0) {
    "Merge clean."
    if ($Build) { Invoke-Build } else { "Run with -Build to verify, then push when ready." }
    exit 0
}

$conflicts = @(git diff --name-only --diff-filter=U)
foreach ($f in $conflicts) {
    if (Resolve-VersionOnlyFile -Path $f) { "auto-resolved version conflict: $f" }
}
$conflicts = @(git diff --name-only --diff-filter=U)
if ($conflicts.Count -eq 0) {
    git commit --no-edit
    if ($LASTEXITCODE -ne 0) { exit 1 }
    "Merge completed."
    if ($Build) { Invoke-Build }
    exit 0
}
"REMAINING CONFLICTS (resolve manually, then re-run this script):"
$conflicts | ForEach-Object { "  $_" }
exit 1
