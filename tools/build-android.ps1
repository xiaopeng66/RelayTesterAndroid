[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArguments = @("assembleDebug")
)

$projectRoot = Split-Path -Parent $PSScriptRoot
$javaHomes = @($env:JAVA_HOME)
$programFilesJava = Join-Path $env:ProgramFiles "Java"
if (Test-Path $programFilesJava) {
    foreach ($jdkDirectory in (Get-ChildItem $programFilesJava -Directory)) {
        $javaHomes += $jdkDirectory.FullName
    }
}

$javaHome = $null
foreach ($candidate in $javaHomes) {
    if ($candidate -and (Test-Path (Join-Path $candidate "bin\javac.exe")) -and (Test-Path (Join-Path $candidate "bin\jar.exe"))) {
        $javaHome = $candidate
        break
    }
}
if (-not $javaHome) {
    throw "No JDK with javac.exe and jar.exe was found. Set JAVA_HOME to JDK 17."
}

$agentSourceDir = Join-Path $PSScriptRoot "gradle-pipe-fallback"
$agentOutputDir = Join-Path $projectRoot "build\\gradle-pipe-fallback"
$agentClassesDir = Join-Path $agentOutputDir "classes"
$agentJar = Join-Path $agentOutputDir "pipe-fallback-agent.jar"
if (-not (Test-Path $agentJar)) {
    New-Item -ItemType Directory -Force -Path $agentClassesDir | Out-Null

    & (Join-Path $javaHome "bin\javac.exe") -d $agentClassesDir `
        (Join-Path $agentSourceDir "PipeFallbackAgent.java")
    if ($LASTEXITCODE -ne 0) {
        throw "Could not compile the Gradle TCP pipe fallback agent."
    }

    & (Join-Path $javaHome "bin\jar.exe") --create --file $agentJar `
        --manifest (Join-Path $agentSourceDir "pipe-fallback-agent.mf") -C $agentClassesDir .
    if ($LASTEXITCODE -ne 0) {
        throw "Could not package the Gradle TCP pipe fallback agent."
    }
}

$previousJavaToolOptions = $env:JAVA_TOOL_OPTIONS
try {
    $agentOption = "-javaagent:$agentJar"
    $env:JAVA_TOOL_OPTIONS = if ([string]::IsNullOrWhiteSpace($previousJavaToolOptions)) {
        $agentOption
    } else {
        "$previousJavaToolOptions $agentOption"
    }
    & (Join-Path $projectRoot "gradlew.bat") @GradleArguments
    $exitCode = $LASTEXITCODE
} finally {
    $env:JAVA_TOOL_OPTIONS = $previousJavaToolOptions
}

exit $exitCode
