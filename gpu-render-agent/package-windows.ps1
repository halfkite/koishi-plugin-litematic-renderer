param(
  [string]$JavaHome = $env:JAVA_HOME,
  [string]$ReleaseDirectory = ''
)
$ErrorActionPreference = 'Stop'
if (-not $PSScriptRoot) { $PSScriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path }
if (-not $JavaHome) { $JavaHome = Split-Path -Parent (Split-Path -Parent (Get-Command java).Source) }
$version = '0.5.1'
$jar = Get-Item "$PSScriptRoot\build\libs\litematic-gpu-agent-$version-all.jar" -ErrorAction SilentlyContinue
if (-not $jar) { throw 'Run gradlew.bat fatJar first.' }

# Keep jpackage input isolated so a stale JAR can never enter the app-image.
$packageInput = "$PSScriptRoot\build\package-input-$version"
if (Test-Path $packageInput) { Remove-Item -Recurse -Force $packageInput }
New-Item -ItemType Directory -Force $packageInput | Out-Null
Copy-Item $jar.FullName (Join-Path $packageInput $jar.Name)

# jpackage's default runtime image does not guarantee bin\java.exe (the launcher is
# stripped), but the Agent needs java.exe to spawn the Minecraft subprocess, so build
# a full-module jlink image and pass it via --runtime-image.
$runtimeImage = "$PSScriptRoot\build\agent-runtime-$version"
if (Test-Path $runtimeImage) { Remove-Item -Recurse -Force $runtimeImage }
$modules = (& "$JavaHome\bin\java.exe" --list-modules | ForEach-Object { ($_ -split '@')[0].Trim() }) -join ','
& "$JavaHome\bin\jlink.exe" --add-modules $modules --output $runtimeImage
if ($LASTEXITCODE -ne 0) { throw "jlink failed with exit code $LASTEXITCODE." }
if (-not (Test-Path "$runtimeImage\bin\java.exe")) { throw 'jlink runtime image is missing bin\java.exe.' }

# Extract-and-run portable build: same layout as the app-image, no installer needed.
$appImageRoot = "$PSScriptRoot\build\jpackage-app-$version"
$appImage = "$appImageRoot\Litematic GPU Agent"
if (Test-Path $appImageRoot) { Remove-Item -Recurse -Force $appImageRoot }
& "$JavaHome\bin\jpackage.exe" --type app-image --name 'Litematic GPU Agent' --app-version $version `
  --input $packageInput --main-jar $jar.Name --main-class dev.qqbot.gpuagent.Main `
  --runtime-image $runtimeImage `
  --dest $appImageRoot --java-options '-Xmx512m'
if ($LASTEXITCODE -ne 0) { throw "jpackage app-image failed with exit code $LASTEXITCODE." }
if (-not (Test-Path "$appImage\runtime\bin\java.exe")) { throw 'app-image is missing runtime\bin\java.exe.' }
$staging = "$PSScriptRoot\build\windows-x64-staging-$version"
if (Test-Path $staging) { Remove-Item -Recurse -Force $staging }
New-Item -ItemType Directory -Force $staging | Out-Null
Copy-Item $appImage (Join-Path $staging 'Litematic GPU Agent') -Recurse
Copy-Item "$PSScriptRoot\README.md" $staging
Copy-Item "$PSScriptRoot\start-agent.bat" $staging
Copy-Item "$PSScriptRoot\start-agent.sh" $staging
if (Test-Path "$PSScriptRoot\LICENSE") { Copy-Item "$PSScriptRoot\LICENSE" $staging }
elseif (Test-Path "$PSScriptRoot\..\LICENSE") { Copy-Item "$PSScriptRoot\..\LICENSE" $staging }
$portableRoot = if ($ReleaseDirectory) { $ReleaseDirectory } else { "$PSScriptRoot\build\distributions" }
New-Item -ItemType Directory -Force $portableRoot | Out-Null
$releasedJar = Join-Path $portableRoot $jar.Name
if ($releasedJar -ne $jar.FullName) { Copy-Item $jar.FullName $releasedJar -Force }
$portable = Join-Path $portableRoot "litematic-gpu-agent-$version-windows-x64.zip"
if (Test-Path $portable) { Remove-Item -Force $portable }
Compress-Archive -Path "$staging\*" -DestinationPath $portable
Write-Host "Portable build: $portable"
