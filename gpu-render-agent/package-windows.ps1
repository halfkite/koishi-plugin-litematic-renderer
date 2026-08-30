param(
  [string]$JavaHome = $env:JAVA_HOME,
  [string]$WixBin = ''
)
$ErrorActionPreference = 'Stop'
if (-not $PSScriptRoot) { $PSScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path }
if (-not $JavaHome) { $JavaHome = Split-Path -Parent (Split-Path -Parent (Get-Command java).Source) }
if ($WixBin) { $env:PATH = "$WixBin;$env:PATH" }
$jar = Get-ChildItem "$PSScriptRoot\build\libs\*-all.jar" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $jar) { throw 'Run gradlew.bat fatJar first.' }
# --input packages the whole directory: drop stale jars from earlier versions
Get-ChildItem "$PSScriptRoot\build\libs\*-all.jar" | Where-Object { $_.FullName -ne $jar.FullName } | Remove-Item -Force

# jpackage's default runtime image does not guarantee bin\java.exe (the launcher is
# stripped), but the Agent needs java.exe to spawn the Minecraft subprocess, so build
# a full-module jlink image and pass it via --runtime-image.
$runtimeImage = "$PSScriptRoot\build\agent-runtime"
if (Test-Path $runtimeImage) { Remove-Item -Recurse -Force $runtimeImage }
$modules = (& "$JavaHome\bin\java.exe" --list-modules | ForEach-Object { ($_ -split '@')[0].Trim() }) -join ','
& "$JavaHome\bin\jlink.exe" --add-modules $modules --output $runtimeImage
if ($LASTEXITCODE -ne 0) { throw "jlink failed with exit code $LASTEXITCODE." }
if (-not (Test-Path "$runtimeImage\bin\java.exe")) { throw 'jlink runtime image is missing bin\java.exe.' }

$version = '0.2.26'

# Extract-and-run portable build: same layout as the installed app, no setup needed.
$appImage = "$PSScriptRoot\build\jpackage-app\Litematic GPU Agent"
if (Test-Path "$PSScriptRoot\build\jpackage-app") { Remove-Item -Recurse -Force "$PSScriptRoot\build\jpackage-app" }
& "$JavaHome\bin\jpackage.exe" --type app-image --name 'Litematic GPU Agent' --app-version $version `
  --input $jar.DirectoryName --main-jar $jar.Name --main-class dev.qqbot.gpuagent.Main `
  --runtime-image $runtimeImage `
  --dest "$PSScriptRoot\build\jpackage-app" --java-options '-Xmx512m'
if ($LASTEXITCODE -ne 0) { throw "jpackage app-image failed with exit code $LASTEXITCODE." }
if (-not (Test-Path "$appImage\runtime\bin\java.exe")) { throw 'app-image is missing runtime\bin\java.exe.' }
$portable = "$PSScriptRoot\build\distributions\litematic-gpu-agent-$version-windows-portable-full.zip"
if (Test-Path $portable) { Remove-Item -Force $portable }
Compress-Archive -Path $appImage -DestinationPath $portable
Write-Host "Portable build: $portable"

# jpackage marks its exe read-only and then refuses to overwrite it on the next run
Get-ChildItem "$PSScriptRoot\build\jpackage\*.exe" -ErrorAction SilentlyContinue | ForEach-Object {
    Set-ItemProperty $_.FullName -Name IsReadOnly -Value $false
    Remove-Item -Force $_.FullName
}
& "$JavaHome\bin\jpackage.exe" --type exe --name 'Litematic GPU Agent' --app-version $version `
  --input $jar.DirectoryName --main-jar $jar.Name --main-class dev.qqbot.gpuagent.Main `
  --runtime-image $runtimeImage `
  --dest "$PSScriptRoot\build\jpackage" --win-menu --win-shortcut --java-options '-Xmx512m'
if ($LASTEXITCODE -ne 0) { throw "jpackage failed with exit code $LASTEXITCODE. Install WiX v4/v5 and add wix.exe to PATH." }
