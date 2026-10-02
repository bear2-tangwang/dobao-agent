<#
.SYNOPSIS
    Step 0 probe: verify Bailian (DashScope) ASR works and check speaker diarization.

.DESCRIPTION
    Full flow: get upload policy -> upload audio -> submit transcription task ->
    poll -> download result JSON -> analyze speakers.
    Standalone script, does not touch the backend project.

.PARAMETER AudioPath
    Path to the audio file (m4a / mp3 / wav ...).

.PARAMETER ApiKey
    Bailian API key. Falls back to env var DASHSCOPE_API_KEY.

.PARAMETER ApiBase
    Bailian API base. Default is the public domain. If your key belongs to a
    workspace, use: https://{WorkspaceId}.cn-beijing.maas.aliyuncs.com/api/v1

.PARAMETER Model
    Transcription model. Default qwen-audio-3.1-asr-flash-filetrans.

.PARAMETER Vocabulary
    Hot words, comma separated (improves technical term accuracy).

.EXAMPLE
    .\interview-asr-probe.ps1 -AudioPath "D:\audio\interview.m4a" -ApiKey "sk-xxxx"

.EXAMPLE
    .\interview-asr-probe.ps1 -AudioPath ".\interview.m4a" `
        -ApiBase "https://ws-ae41waoyh0yzb53i.cn-beijing.maas.aliyuncs.com/api/v1" `
        -Vocabulary "Kafka,Redis,MySQL"
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$AudioPath,

    [string]$ApiKey = $env:DASHSCOPE_API_KEY,

    [string]$ApiBase = "https://dashscope.aliyuncs.com/api/v1",

    [string]$Model = "qwen-audio-3.1-asr-flash-filetrans",

    [string]$Vocabulary = "",

    # HTTP proxy, e.g. http://127.0.0.1:7890
    # Leave empty to auto-detect from Windows system settings.
    # Pass "none" to force a direct connection.
    [string]$Proxy = "",

    # Print the requests that would be sent, without calling the API.
    [switch]$DryRun,

    [int]$PollIntervalSec = 5,

    [int]$TimeoutSec = 900
)

$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$OutputEncoding = [System.Text.Encoding]::UTF8

function Write-Step($msg) { Write-Host ""; Write-Host "=== $msg ===" -ForegroundColor Cyan }
function Write-Ok($msg)   { Write-Host "  [OK] $msg" -ForegroundColor Green }
function Write-Warn2($msg){ Write-Host "  [!]  $msg" -ForegroundColor Yellow }

function Format-Ts([long]$ms) {
    # NOTE: "{0:00}:{1:00}" does NOT zero-pad ints in .NET; it renders "1:02" for
    # 62 seconds. Pad explicitly so short timestamps stay readable/sortable.
    $t = [TimeSpan]::FromMilliseconds($ms)
    $m = [int]$t.TotalMinutes
    return ("{0}:{1}" -f $m.ToString("00"), $t.Seconds.ToString("00"))
}

# ---------- helper: build multipart/form-data body ----------
# NOTE: do NOT use New-Object for ByteArrayContent here. In Windows PowerShell 5.1
# New-Object flattens a byte[] into one constructor argument per element, which
# fails for any real file:
#   "Cannot find an overload for ByteArrayContent and the count is <filesize>"
# Building the body manually keeps the array intact and works on both PS 5.1 and 7.
function Build-MultipartContent {
    param(
        [hashtable]$TextField,
        [string]$FilePath,
        [string]$FileFieldName = "file"
    )

    $boundary = [System.Guid]::NewGuid().ToString("N")
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    $ms = New-Object System.IO.MemoryStream

    $sb = New-Object System.Text.StringBuilder
    foreach ($name in $TextField.Keys) {
        [void]$sb.Append("--$boundary`r`n")
        [void]$sb.Append("Content-Disposition: form-data; name=`"$name`"`r`n")
        [void]$sb.Append("`r`n")
        [void]$sb.Append("$($TextField[$name])`r`n")
    }
    $headBytes = $utf8NoBom.GetBytes($sb.ToString())
    $ms.Write($headBytes, 0, $headBytes.Length)

    if ($FilePath) {
        # IMPORTANT: OSS upload forms only understand the plain filename="..." form.
        # Do NOT use filename*=UTF-8''... (RFC 5987): OSS fails to parse it, falls
        # back to treating the whole body as one text field, and rejects the request
        # with FieldItemTooLong / ProposedSize = <file size in bytes>.
        # The real (possibly non-ASCII) filename travels in the `key` form field.
        $fileHeader = "--$boundary`r`n" +
                      "Content-Disposition: form-data; name=`"$FileFieldName`"; filename=`"file`"`r`n" +
                      "Content-Type: application/octet-stream`r`n" +
                      "`r`n"
        $fileHeaderBytes = $utf8NoBom.GetBytes($fileHeader)
        $ms.Write($fileHeaderBytes, 0, $fileHeaderBytes.Length)

        $fileBytes = [System.IO.File]::ReadAllBytes($FilePath)
        $ms.Write($fileBytes, 0, $fileBytes.Length)

        $crlf = $utf8NoBom.GetBytes("`r`n")
        $ms.Write($crlf, 0, $crlf.Length)
    }

    $closing = $utf8NoBom.GetBytes("--$boundary--`r`n")
    $ms.Write($closing, 0, $closing.Length)
    $ms.Position = 0

    $streamContent = New-Object System.Net.Http.StreamContent($ms)
    $streamContent.Headers.ContentType =
        [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("multipart/form-data; boundary=$boundary")

    return @{ Content = $streamContent; Stream = $ms }
}

# ---------- helper: percent-encode an oss:// URL for API submission ----------
# Bailian requires the file URL to be percent-encoded when the path contains
# spaces, Chinese characters or other reserved characters; otherwise the submit
# call returns HTTP 400.
# Encode per path segment so the "/" separators survive.
function ConvertTo-EncodedOssUrl([string]$ossUrl) {
    $prefix = "oss://"
    if (-not $ossUrl.StartsWith($prefix)) { return $ossUrl }
    $path = $ossUrl.Substring($prefix.Length)
    $segments = $path -split "/"
    $encoded = @()
    foreach ($s in $segments) {
        if ([string]::IsNullOrEmpty($s)) { continue }
        $encoded += [System.Uri]::EscapeDataString($s)
    }
    return $prefix + ($encoded -join "/")
}

# ---------- proxy setup ----------
# Windows PowerShell 5.1 does not pick up the system proxy for .NET calls,
# which makes every request fail with "The underlying connection was closed".
# Resolve it explicitly and install it as the process-wide default.
function Resolve-ProxySetting([string]$requested) {
    if ($requested -eq "none") { return $null }
    if (-not [string]::IsNullOrWhiteSpace($requested)) { return $requested }

    try {
        $reg = Get-ItemProperty -Path "HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings" -ErrorAction SilentlyContinue
        if ($reg -and $reg.ProxyEnable -eq 1 -and -not [string]::IsNullOrWhiteSpace($reg.ProxyServer)) {
            $server = $reg.ProxyServer
            # ProxyServer may be "host:port" or "http=host:port;https=host:port"
            if ($server -match "=") {
                $httpsPart = ($server -split ";") | Where-Object { $_ -match "^https=" } | Select-Object -First 1
                if ($httpsPart) { $server = ($httpsPart -split "=")[1] }
                else { $server = ($server -split ";")[0].Split("=")[1] }
            }
            if ($server -notmatch "^https?://") { $server = "http://$server" }
            return $server
        }
    } catch {
        # registry not readable, fall through to direct connection
    }
    return $null
}

$proxyUrl = Resolve-ProxySetting $Proxy
if ($proxyUrl) {
    $webProxy = New-Object System.Net.WebProxy($proxyUrl, $true)
    [System.Net.WebRequest]::DefaultWebProxy = $webProxy
    if ([string]::IsNullOrWhiteSpace($Proxy)) { $proxySource = "system settings" } else { $proxySource = "parameter" }
    Write-Host "  Proxy     : $proxyUrl (from $proxySource)"
} else {
    [System.Net.WebRequest]::DefaultWebProxy = $null
    Write-Host "  Proxy     : direct (no proxy)"
}

# ---------- 0. preflight ----------
if ([string]::IsNullOrWhiteSpace($ApiKey)) {
    throw "Missing API key. Set env DASHSCOPE_API_KEY or pass -ApiKey."
}
if (-not (Test-Path -LiteralPath $AudioPath)) {
    throw "Audio file not found: $AudioPath"
}

$audioFile = Get-Item -LiteralPath $AudioPath
$sizeMB = [math]::Round($audioFile.Length / 1MB, 2)

Write-Step "0. Preflight"
Write-Host "  File      : $($audioFile.FullName)"
Write-Host "  Size      : $sizeMB MB"
Write-Host "  Model     : $Model"
Write-Host "  API base  : $ApiBase"
Write-Host "  Dry run   : $([bool]$DryRun)"

if ($sizeMB -gt 80) {
    Write-Warn2 "File exceeds 80MB; the production feature would reject this. Probe continues."
}
if ($sizeMB -gt 1000) {
    throw "File exceeds 1GB; Bailian upload policy API will not accept it."
}

$authHeader = @{ Authorization = "Bearer $ApiKey" }

# ---------- DryRun helper ----------
function Show-Request($label, $method, $url, $headers, $body) {
    Write-Host ""
    Write-Host "  >> $label" -ForegroundColor DarkGray
    Write-Host "     $method $url"
    if ($headers) {
        foreach ($k in $headers.Keys) {
            $v = if ($k -eq "Authorization") { "Bearer sk-***hidden***" } else { $headers[$k] }
            Write-Host "     ${k}: $v"
        }
    }
    if ($body) { Write-Host "     body: $body" }
}

# ---------- 1. get upload policy ----------
Write-Step "1. Get upload policy"

$policyUrl = "$ApiBase/uploads?action=getPolicy&model=$Model"

if ($DryRun) {
    Show-Request "Get upload policy" "GET" $policyUrl $authHeader $null
    Write-Host ""
    Write-Ok "Dry run finished. No API call was made."
    Write-Host ""
    Write-Host "  Remove -DryRun to actually run the probe." -ForegroundColor White
    return
}

try {
    $policyResp = Invoke-RestMethod -Uri $policyUrl -Headers $authHeader -Method Get
} catch {
    throw "Failed to get upload policy: $($_.Exception.Message)`nCheck: (1) API key valid? (2) ApiBase matches your key region? (3) proxy needed? try -Proxy http://127.0.0.1:7890"
}

$policy = $policyResp.data
if (-not $policy) {
    throw "Unexpected upload policy response: $($policyResp | ConvertTo-Json -Depth 5)"
}
Write-Ok "Policy acquired. upload_dir = $($policy.upload_dir)"

# ---------- 2. upload audio to Bailian temp storage ----------
Write-Step "2. Upload audio ($sizeMB MB, please wait)"

# The `key` form field names the object and carries the real filename (the file
# part uses a plain ASCII filename="file" instead, see Build-MultipartContent).
$key = "$($policy.upload_dir)/$($audioFile.Name)"
$uploadHost = $policy.upload_host

Write-Host "  OSS key   : $key"

Add-Type -AssemblyName System.Net.Http

# HttpClient does not inherit WebRequest.DefaultWebProxy, wire it explicitly
if ($proxyUrl) {
    $httpHandler = New-Object System.Net.Http.HttpClientHandler
    $httpHandler.Proxy = New-Object System.Net.WebProxy($proxyUrl, $true)
    $httpHandler.UseProxy = $true
    $httpClient = New-Object System.Net.Http.HttpClient($httpHandler)
} else {
    $httpHandler = New-Object System.Net.Http.HttpClientHandler
    $httpHandler.UseProxy = $false
    $httpClient = New-Object System.Net.Http.HttpClient($httpHandler)
}
$httpClient.Timeout = [TimeSpan]::FromMinutes(20)

$uploadHandle = $null
try {
    $textFields = [ordered]@{
        "OSSAccessKeyId"         = [string]$policy.oss_access_key_id
        "Signature"              = [string]$policy.signature
        "policy"                 = [string]$policy.policy
        "x-oss-object-acl"       = [string]$policy.x_oss_object_acl
        "x-oss-forbid-overwrite" = [string]$policy.x_oss_forbid_overwrite
        "key"                    = [string]$key
        "success_action_status"  = "200"
    }

    $uploadHandle = Build-MultipartContent -TextField $textFields -FilePath $audioFile.FullName

    $uploadTask = $httpClient.PostAsync($uploadHost, $uploadHandle.Content)
    $uploadTask.Wait()
    $uploadResp = $uploadTask.Result

    if (-not $uploadResp.IsSuccessStatusCode) {
        $body = $uploadResp.Content.ReadAsStringAsync().Result
        throw "Upload failed HTTP $([int]$uploadResp.StatusCode): $body"
    }

    $ossUrl = "oss://$key"
    Write-Ok "Upload succeeded"
    Write-Host "  Temp URL  : $ossUrl"
    Write-Host "  Valid for : 48 hours"
}
finally {
    if ($uploadHandle) {
        if ($uploadHandle.Content) { $uploadHandle.Content.Dispose() }
        if ($uploadHandle.Stream) { $uploadHandle.Stream.Dispose() }
    }
    if ($httpClient) { $httpClient.Dispose() }
}

# ---------- 3. submit transcription task ----------
Write-Step "3. Submit transcription task (diarization ON)"

$params = @{
    channel_id          = @(0)
    diarization_enabled = $true
    speaker_count       = 2
    language_hints      = @("zh", "en")
}
if (-not [string]::IsNullOrWhiteSpace($Vocabulary)) {
    $vocab = @{}
    foreach ($w in ($Vocabulary -split ",")) {
        $t = $w.Trim()
        if ($t) { $vocab[$t] = 5 }
    }
    $params["vocabulary"] = $vocab
    Write-Host "  Hot words : $($vocab.Keys -join ', ')"
}

# Bailian needs the file URL percent-encoded before submission
$ossUrlEncoded = ConvertTo-EncodedOssUrl $ossUrl
if ($ossUrlEncoded -ne $ossUrl) {
    Write-Host "  Encoded   : $ossUrlEncoded"
}

$submitBody = @{
    model      = $Model
    input      = @{ file_urls = @($ossUrlEncoded) }
    parameters = $params
} | ConvertTo-Json -Depth 8 -Compress

$submitHeaders = @{
    Authorization                    = "Bearer $ApiKey"
    "Content-Type"                   = "application/json"
    "X-DashScope-Async"              = "enable"
    "X-DashScope-OssResourceResolve" = "enable"
}

if ($DryRun) {
    Show-Request "Submit transcription task" "POST" "$ApiBase/services/audio/asr/transcription" $submitHeaders $submitBody
}

try {
    $submitResp = Invoke-RestMethod -Uri "$ApiBase/services/audio/asr/transcription" `
        -Headers $submitHeaders -Method Post -Body $submitBody
} catch {
    $detail = ""
    $webResp = $_.Exception.Response
    if ($webResp -and $webResp.GetResponseStream) {
        try {
            $reader = New-Object System.IO.StreamReader($webResp.GetResponseStream())
            $detail = $reader.ReadToEnd()
            $reader.Close()
        } catch { }
    }
    throw "Failed to submit task: $($_.Exception.Message)`nRequest body: $submitBody`nResponse body: $detail"
}

$taskId = $submitResp.output.task_id
if (-not $taskId) {
    throw "No task_id returned: $($submitResp | ConvertTo-Json -Depth 6)"
}
Write-Ok "Task submitted. task_id = $taskId"

# ---------- 4. poll ----------
Write-Step "4. Polling task status (max $TimeoutSec s)"

$taskUrl = "$ApiBase/tasks/$taskId"
$startTime = Get-Date
$resultUrl = $null

while ($true) {
    $elapsed = [int]((Get-Date) - $startTime).TotalSeconds
    if ($elapsed -gt $TimeoutSec) {
        throw "Polling timeout (${TimeoutSec}s). Query manually later: $taskUrl"
    }

    Start-Sleep -Seconds $PollIntervalSec

    try {
        $taskResp = Invoke-RestMethod -Uri $taskUrl -Headers $authHeader -Method Get
    } catch {
        Write-Warn2 "Query failed, retrying: $($_.Exception.Message)"
        continue
    }

    $status = $taskResp.output.task_status
    Write-Host "  [$elapsed s] status: $status"

    if ($status -eq "SUCCEEDED") {
        $sub = $taskResp.output.results[0]
        if ($sub.subtask_status -ne "SUCCEEDED") {
            throw "Subtask failed: code=$($sub.code) message=$($sub.message)"
        }
        $resultUrl = $sub.transcription_url
        break
    }
    elseif ($status -eq "FAILED" -or $status -eq "UNKNOWN") {
        throw "Task failed. Full response:`n$($taskResp | ConvertTo-Json -Depth 8)"
    }
}

Write-Ok "Transcription done"

# ---------- 5. download result JSON ----------
Write-Step "5. Download result (URL expires in 24h, saving now)"

$outDir = Join-Path (Get-Location) "probe-output"
if (-not (Test-Path -LiteralPath $outDir)) {
    New-Item -ItemType Directory -Path $outDir | Out-Null
}
$baseName = [System.IO.Path]::GetFileNameWithoutExtension($audioFile.Name)
$rawJsonPath = Join-Path $outDir "$baseName-asr-raw.json"
$readablePath = Join-Path $outDir "$baseName-transcript.txt"

# Download the result with HttpClient so we get raw BYTES.
# Invoke-WebRequest decodes the body as Latin-1 when the server omits an explicit
# charset, which silently corrupts every Chinese character (double-encoding).
$dlClient = New-Object System.Net.Http.HttpClient
try {
    $rawResp = $dlClient.GetAsync($resultUrl).Result
    if (-not $rawResp.IsSuccessStatusCode) {
        throw "Failed to download result: HTTP $([int]$rawResp.StatusCode)"
    }
    $rawBytes = $rawResp.Content.ReadAsByteArrayAsync().Result
}
finally {
    $dlClient.Dispose()
}

$rawJson = [System.Text.Encoding]::UTF8.GetString($rawBytes)
[System.IO.File]::WriteAllText($rawJsonPath, $rawJson, (New-Object System.Text.UTF8Encoding($false)))
Write-Ok "Raw JSON: $rawJsonPath ($($rawBytes.Length) bytes)"

$parsed = $rawJson | ConvertFrom-Json

# ---------- 6. analyze ----------
Write-Step "6. Analysis"

$props = $parsed.properties
Write-Host "  audio_format      : $($props.audio_format)"
Write-Host "  sampling_rate     : $($props.original_sampling_rate) Hz"
Write-Host "  channels          : $($props.channels -join ', ')"
Write-Host "  original_duration : $([math]::Round($props.original_duration_in_milliseconds / 1000, 1)) s"

$sentences = @()
foreach ($t in $parsed.transcripts) {
    Write-Host "  speech_duration   : $([math]::Round($t.content_duration_in_milliseconds / 1000, 1)) s (billing basis)"
    foreach ($s in $t.sentences) { $sentences += $s }
}

Write-Host "  sentence count    : $($sentences.Count)"

if ($sentences.Count -eq 0) {
    Write-Warn2 "No sentences recognized. Possible causes: no speech, wrong language hint, bad audio."
    return
}

$bySpeaker = $sentences | Group-Object -Property speaker_id | Sort-Object Name

Write-Host ""
Write-Host "  --- speaker distribution ---" -ForegroundColor Magenta
foreach ($g in $bySpeaker) {
    $dur = 0
    $hasTime = $true
    foreach ($s in $g.Group) {
        if ($null -ne $s.begin_time -and $null -ne $s.end_time) {
            $dur += ($s.end_time - $s.begin_time)
        } else { $hasTime = $false }
    }
    $durStr = if ($hasTime) { "$([math]::Round($dur / 1000, 1)) s" } else { "unknown" }
    Write-Host ("    speaker_id={0}  sentences={1,-4} speech_time={2}" -f $g.Name, $g.Count, $durStr)
}

$speakerCount = $bySpeaker.Count
Write-Host ""
if ($speakerCount -eq 1) {
    Write-Host "  >>> RESULT: only 1 speaker detected <<<" -ForegroundColor Red
    Write-Host "      Either the audio really has one voice, or the two voices differ too much" -ForegroundColor Red
    Write-Host "      for the model to separate. If this is a real 2-person interview," -ForegroundColor Red
    Write-Host "      the approach needs rework." -ForegroundColor Red
}
elseif ($speakerCount -eq 2) {
    Write-Host "  >>> RESULT: 2 speakers detected - diarization is usable <<<" -ForegroundColor Green
}
else {
    Write-Host "  >>> RESULT: $speakerCount speakers detected <<<" -ForegroundColor Yellow
    Write-Host "      Same person may be split into several IDs (fragmentation)." -ForegroundColor Yellow
    Write-Host "      Production code must merge nearby fragments, or force speaker_count=2." -ForegroundColor Yellow
}

# export readable transcript
$sb = New-Object System.Text.StringBuilder
[void]$sb.AppendLine("# Transcript grouped by speaker")
[void]$sb.AppendLine("")
foreach ($s in $sentences) {
    $ts = if ($null -ne $s.begin_time) { Format-Ts $s.begin_time } else { "--:--" }
    $spk = if ($null -ne $s.speaker_id) { "Speaker$($s.speaker_id)" } else { "Unknown" }
    [void]$sb.AppendLine("[$ts] [$spk] $($s.text)")
}
[System.IO.File]::WriteAllText($readablePath, $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))
Write-Ok "Readable transcript: $readablePath"

Write-Host ""
Write-Host "  --- first 15 sentences ---" -ForegroundColor Magenta
$preview = $sentences | Select-Object -First 15
foreach ($s in $preview) {
    $spk = if ($null -ne $s.speaker_id) { "S$($s.speaker_id)" } else { "S?" }
    $text = $s.text
    if ($text.Length -gt 60) { $text = $text.Substring(0, 60) + "..." }
    Write-Host ("    {0}  {1}" -f $spk, $text)
}

# ---------- 7. conclusion ----------
Write-Step "7. Conclusion and next steps"
Write-Host ""
Write-Host "  Check these three things manually:"
Write-Host ""
Write-Host "  1) Transcription accuracy: are technical terms / names badly wrong in the preview?"
Write-Host "     -> If yes, rerun with -Vocabulary to add hot words."
Write-Host ""
Write-Host "  2) Speaker separation: do S0 / S1 consistently map to two different people?"
Write-Host "     -> only one speaker_id  = diarization failed, approach needs rework"
Write-Host "     -> three or more        = fragmentation, production code must merge"
Write-Host ""
Write-Host "  3) Who is the interviewer: paste the first ~100 lines of"
Write-Host "     $readablePath"
Write-Host "     into any LLM and ask:"
Write-Host "     'Which speaker is the interviewer? Answer with speaker_id and reason only.'"
Write-Host "     -> Judge whether it is confident and correct."
Write-Host ""
Write-Host "  If all three pass, proceed to coding step 1."
Write-Host ""
Write-Host "  Output files:"
Write-Host "    $rawJsonPath"
Write-Host "    $readablePath"
Write-Host ""
