<#
.SYNOPSIS
    Make (or reuse) Family Coverage's release signing key, keep it in Bitwarden, and give the release workflow what it
    needs. The maintainer runs this once, on release day, after the repository is public. Nothing prints the key or its
    password.

.DESCRIPTION
    1. Bitwarden: finds the secure note "family-coverage (Android signing key)". If it's missing, this makes a 4096-bit
       RSA key, "CN=Family Coverage, O=StorieDev", valid 100 years, in a PKCS12 keystore with a random password
       (keytool reads it from the environment, never the command line), and saves the note: the keystore as an
       attachment, key_alias and keystore_password as fields.
    2. GitHub: makes the "release" environment wait for the maintainer's approval, for v* tags only, and stores the
       keystore and its password as that environment's secrets. GitHub's free plan allows the approval step only on
       public repositories, so the repository must be public first.
    3. Pins the certificate's SHA-256 (public) in .github/workflows/release.yml, so a release signed by any other key
       stops. Commit that change.
    Re-runnable: an existing note is reused, and the environment and secrets are updated in place. An unlocked
    Bitwarden session in BW_SESSION is used as it is; otherwise this asks for the master password.

    -DryRun touches neither Bitwarden nor GitHub nor the workflow: it makes a throwaway key in a temporary folder,
    builds the Bitwarden note without saving it, reads GitHub's current settings, and checks the pin would apply.

.EXAMPLE
    pwsh -NoProfile -File C:\Code\family-coverage\tool\New-SigningKey.ps1
.EXAMPLE
    pwsh -NoProfile -File C:\Code\family-coverage\tool\New-SigningKey.ps1 -DryRun
#>
param(
    [string]$Repo = 'sstangle73/family-coverage',
    [string]$ItemName = 'family-coverage (Android signing key)',
    [string]$Folder = 'Machine secrets',
    [string]$Keytool = 'C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe',
    [switch]$DryRun
)
$ErrorActionPreference = 'Stop'
$results = [ordered]@{}
$tmp = Join-Path $env:TEMP ('fc-key-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $tmp | Out-Null
# bw reads and writes UTF-8; PowerShell would otherwise decode its output in the terminal's code page.
$utf8 = [Text.UTF8Encoding]::new($false)
$savedConsole = [Console]::OutputEncoding
$OutputEncoding = $utf8
[Console]::OutputEncoding = $utf8

function Set-GitHubSecret([string]$Name, [string]$Value) {
    # From a file on stdin, with no trailing newline: never on a command line.
    $file = Join-Path $tmp "$Name.txt"
    [IO.File]::WriteAllText($file, $Value)
    $p = Start-Process -FilePath gh -ArgumentList @('secret', 'set', $Name, '--env', 'release', '--repo', $Repo) `
        -RedirectStandardInput $file -NoNewWindow -Wait -PassThru
    Remove-Item $file -Force
    if ($p.ExitCode -ne 0) { throw "gh secret set $Name failed (exit $($p.ExitCode))" }
}

function New-NoteJson([string]$FolderId, [string]$Alias, [string]$Password) {
    # Built whole, not from `bw get template item`: bw 2026.5 leaves null fields out of its template, so assigning
    # folderId to it fails.
    [ordered]@{
        organizationId = $null; collectionIds = $null; folderId = $(if ($FolderId) { $FolderId } else { $null })
        type = 2; name = $ItemName; favorite = $false; reprompt = 0
        notes = "Release signing key for Family Coverage (com.storiedev.familycoverage), made $(Get-Date -Format yyyy-MM-dd). " +
            "It signs the GitHub releases and uploads to Google Play. Losing it means GitHub users can't update: they'd " +
            "have to uninstall and lose their recordings. GitHub holds a copy as the 'release' environment's secrets. " +
            "Script: tool/New-SigningKey.ps1 in $Repo."
        fields = @(
            [ordered]@{ name = 'key_alias'; value = $Alias; type = 0 },
            [ordered]@{ name = 'keystore_password'; value = $Password; type = 1 }
        )
        secureNote = [ordered]@{ type = 0 }; login = $null; card = $null; identity = $null
    } | ConvertTo-Json -Depth 10 -Compress
}

function New-Keystore([string]$Path, [string]$Alias, [string]$Password) {
    $env:FC_KS_PW = $Password
    & $Keytool -genkeypair -keystore $Path -storetype PKCS12 -alias $Alias -keyalg RSA -keysize 4096 `
        -validity 36500 -dname 'CN=Family Coverage, O=StorieDev' -storepass:env FC_KS_PW -keypass:env FC_KS_PW 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $Path)) { throw 'keytool could not make the keystore' }
}

function New-Password {
    $bytes = New-Object byte[] 24
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    ([Convert]::ToBase64String($bytes)) -replace '[+/=]', 'x'
}

try {
    if ((gh repo view $Repo --json visibility | ConvertFrom-Json).visibility -ne 'PUBLIC') {
        throw "$Repo is still private: make it public first (GitHub's free plan has no approval step on private repositories)."
    }
    if (-not (Test-Path $Keytool)) { throw "keytool not found at $Keytool" }

    # 1. The key, from Bitwarden or new.
    $ks = Join-Path $tmp 'release.p12'
    $alias = 'familycoverage'
    if ($DryRun) {
        $pw = New-Password
        New-Keystore $ks $alias $pw
        $json = New-NoteJson 'folder-id' $alias $pw
        $back = $json | ConvertFrom-Json
        $fieldsOk = ($back.fields | Where-Object { $_.name -eq 'keystore_password' -and $_.type -eq 1 }) -and
            ($back.fields | Where-Object { $_.name -eq 'key_alias' -and $_.value -eq $alias })
        if (-not ($back.type -eq 2 -and $back.folderId -eq 'folder-id' -and $back.name -eq $ItemName -and $fieldsOk)) {
            throw 'the Bitwarden note JSON is wrong'
        }
        $results['Bitwarden (dry run: throwaway key, note built, not saved)'] = 'OK'
    } else {
        if (-not $env:BW_SESSION) { $env:BW_SESSION = bw unlock --raw }
        if (-not $env:BW_SESSION) {
            throw "Bitwarden didn't unlock (a mistyped master password is the usual cause). Nothing was changed: run it again."
        }
        bw sync | Out-Null
        $existing = @(bw list items --search $ItemName | ConvertFrom-Json | Where-Object { $_.name -eq $ItemName })
        if ($existing.Count -gt 1) { throw "More than one Bitwarden item is named '$ItemName'; keep one and run again." }
        if ($existing.Count -eq 1) {
            $item = bw get item $existing[0].id | ConvertFrom-Json
            $pw = ($item.fields | Where-Object { $_.name -eq 'keystore_password' }).value
            $alias = ($item.fields | Where-Object { $_.name -eq 'key_alias' }).value
            $att = $item.attachments | Where-Object { $_.fileName -eq 'release.p12' } | Select-Object -First 1
            if (-not $pw -or -not $alias -or -not $att) { throw "'$ItemName' lacks keystore_password, key_alias or release.p12" }
            bw get attachment $att.id --itemid $item.id --output $ks | Out-Null
            Write-Host "Bitwarden: reusing '$ItemName'."
        } else {
            $pw = New-Password
            New-Keystore $ks $alias $pw
            $folderId = (@(bw list folders | ConvertFrom-Json | Where-Object { $_.name -eq $Folder }) | Select-Object -First 1).id
            $created = New-NoteJson $folderId $alias $pw | bw encode | bw create item | ConvertFrom-Json
            if ($LASTEXITCODE -ne 0 -or -not $created.id) { throw 'Bitwarden: bw create item failed' }
            bw create attachment --file $ks --itemid $created.id | Out-Null
            $check = bw get item $created.id | ConvertFrom-Json
            if (-not ($check.attachments | Where-Object { $_.fileName -eq 'release.p12' })) { throw 'Bitwarden: the attachment is missing' }
            $created = $null
            Write-Host "Bitwarden: saved '$ItemName' with release.p12."
        }
        $results['Bitwarden'] = 'OK'
    }

    $env:FC_KS_PW = $pw
    $listing = & $Keytool -list -v -keystore $ks -storetype PKCS12 -storepass:env FC_KS_PW 2>&1
    $shaLine = $listing | Select-String -Pattern 'SHA256:' | Select-Object -First 1
    if (-not $shaLine) { throw 'could not read the certificate fingerprint' }
    $sha = ($shaLine.ToString() -replace '.*SHA256:\s*', '' -replace ':', '').Trim().ToLower()
    if ($sha -notmatch '^[0-9a-f]{64}$') { throw 'the certificate fingerprint looks wrong' }

    # 2. GitHub: the environment, its approval and tag rule, then the secrets.
    $me = (gh api user | ConvertFrom-Json).id
    if ($DryRun) {
        $envNow = gh api "repos/$Repo/environments/release" 2>$null | ConvertFrom-Json
        $rules = if ($envNow) { @($envNow.protection_rules | ForEach-Object { $_.type }) -join ', ' } else { 'none' }
        $results["GitHub (dry run: you are user $me; release environment rules now: $(if ($rules) { $rules } else { 'none' }))"] = 'OK'
    } else {
        $envBody = Join-Path $tmp 'environment.json'
        [ordered]@{ wait_timer = 0; prevent_self_review = $false; reviewers = @(@{ type = 'User'; id = $me })
                    deployment_branch_policy = [ordered]@{ protected_branches = $false; custom_branch_policies = $true } } |
            ConvertTo-Json -Depth 5 | Set-Content -Path $envBody -Encoding utf8
        gh api -X PUT "repos/$Repo/environments/release" --input $envBody | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'GitHub refused the release environment (see above).' }
        $policies = (gh api "repos/$Repo/environments/release/deployment-branch-policies" | ConvertFrom-Json).branch_policies
        if (-not ($policies | Where-Object { $_.name -eq 'v*' -and $_.type -eq 'tag' })) {
            gh api -X POST "repos/$Repo/environments/release/deployment-branch-policies" -f name='v*' -f type=tag | Out-Null
            if ($LASTEXITCODE -ne 0) { throw 'GitHub refused the v* tag rule.' }
        }
        $results['GitHub environment (approval, v* tags)'] = 'OK'
        Set-GitHubSecret 'FC_KEYSTORE_B64' ([Convert]::ToBase64String([IO.File]::ReadAllBytes($ks)))
        Set-GitHubSecret 'FC_KEYSTORE_PASSWORD' $pw
        Set-GitHubSecret 'FC_KEY_ALIAS' $alias
        $results['GitHub secrets'] = 'OK'
    }

    # 3. The pin.
    $wf = Join-Path $PSScriptRoot '..\.github\workflows\release.yml'
    $text = [IO.File]::ReadAllText($wf)
    $pinned = [regex]::Replace($text, 'FC_CERT_SHA256: "[^"]*"', "FC_CERT_SHA256: `"$sha`"")
    if ($DryRun) {
        $results['release.yml pin (dry run: not written)'] = if ($pinned -match [regex]::Escape($sha)) { 'OK' } else { 'FAILED' }
    } else {
        [IO.File]::WriteAllText($wf, $pinned)
        $results['release.yml pin'] = if ($pinned -match [regex]::Escape($sha)) { 'OK' } else { 'FAILED' }
        Write-Host ''
        Write-Host 'Certificate SHA-256 (public), now pinned in .github/workflows/release.yml: commit that change.'
        Write-Host $sha
    }
} finally {
    $pw = $null
    Remove-Item Env:FC_KS_PW -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
    [Console]::OutputEncoding = $savedConsole
    Write-Host ''
    foreach ($k in $results.Keys) { Write-Host ("{0,-40} {1}" -f $k, $results[$k]) }
}
