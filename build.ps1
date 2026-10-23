param(
    [switch]$Install, [switch]$Test, [switch]$Release,
    [string]$SdkRoot = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidJar, [string]$BuildTools, [string]$AdbPath,
    [string]$WorkRoot = [System.IO.Path]::GetTempPath(),
    [string]$KeystorePath, [string]$KeyAlias = 'androiddebugkey'
)
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
if (-not $SdkRoot) { $SdkRoot = $env:ANDROID_SDK_ROOT }
if (-not $AndroidJar -and $SdkRoot) { $AndroidJar = Join-Path $SdkRoot 'platforms/android-36/android.jar' }
if (-not $BuildTools -and $SdkRoot) { $BuildTools = Join-Path $SdkRoot 'build-tools/36.0.0' }
if (-not $AndroidJar -or -not (Test-Path -LiteralPath $AndroidJar)) { throw 'Set ANDROID_HOME or pass -AndroidJar for Android 36.' }
if (-not $BuildTools -or -not (Test-Path -LiteralPath $BuildTools)) { throw 'Set ANDROID_HOME or pass -BuildTools for Build Tools 36.0.0.' }
if (-not $KeystorePath) { $KeystorePath = Join-Path $projectRoot 'local-debug.keystore' }
$KeystorePath = [System.IO.Path]::GetFullPath($KeystorePath)

function Find-Executable([string]$Directory, [string]$Name) {
    if ($Directory) {
        foreach ($filename in @("$Name.exe", $Name)) {
            $candidate = Join-Path $Directory $filename
            if (Test-Path -LiteralPath $candidate) { return $candidate }
        }
        throw "Executable missing: $Name in $Directory"
    }
    return (Get-Command $Name -ErrorAction Stop).Source
}
function Check-Exit([string]$Step) { if ($LASTEXITCODE -ne 0) { throw "${Step} failed: $LASTEXITCODE" } }
$javaBin = if ($JavaHome) { Join-Path $JavaHome 'bin' } else { '' }
$java = Find-Executable $javaBin 'java'
$javac = Find-Executable $javaBin 'javac'
$jar = Find-Executable $javaBin 'jar'
$keytool = Find-Executable $javaBin 'keytool'
$aapt2 = Find-Executable $BuildTools 'aapt2'
$zipalign = Find-Executable $BuildTools 'zipalign'
if ($Install -and -not $AdbPath) {
    $adbDirectory = if ($SdkRoot) { Join-Path $SdkRoot 'platform-tools' } else { '' }
    $AdbPath = Find-Executable $adbDirectory 'adb'
}
$workFull = [System.IO.Path]::GetFullPath($WorkRoot)
if ($env:OS -eq 'Windows_NT' -and $workFull -match '[^\x00-\x7F]') { throw 'Use an ASCII work directory, e.g. -WorkRoot C:/Temp' }
$stage = Join-Path $workFull ('longpage-' + [guid]::NewGuid().ToString('N'))
$output = Join-Path $stage 'out'
$oldStorePassword = $env:LONGPAGE_STORE_PASSWORD
$oldKeyPassword = $env:LONGPAGE_KEY_PASSWORD
if (-not $env:LONGPAGE_STORE_PASSWORD) { $env:LONGPAGE_STORE_PASSWORD = 'android' }
if (-not $env:LONGPAGE_KEY_PASSWORD) { $env:LONGPAGE_KEY_PASSWORD = $env:LONGPAGE_STORE_PASSWORD }
try {
    New-Item -ItemType Directory -Path $stage -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $projectRoot 'app') -Destination $stage -Recurse
    if ($Release) {
        $manifestPath = Join-Path $stage 'app/src/main/AndroidManifest.xml'
        $manifest = [System.IO.File]::ReadAllText($manifestPath).Replace('android:debuggable="true"', 'android:debuggable="false"')
        [System.IO.File]::WriteAllText($manifestPath, $manifest, [System.Text.UTF8Encoding]::new($false))
    }
    Copy-Item -LiteralPath $AndroidJar -Destination (Join-Path $stage 'android.jar')
    $stagedJar = Join-Path $stage 'android.jar'
    foreach ($name in @('generated', 'classes', 'dex', 'tests')) { New-Item -ItemType Directory -Path (Join-Path $output $name) -Force | Out-Null }
    if ($Test) {
        & $javac -encoding UTF-8 -d "$output/tests" "$stage/app/src/main/java/local/longcapture/OverlapMatcher.java" "$projectRoot/tests/MatcherTest.java"
        Check-Exit 'Compile tests'
        & $java -cp "$output/tests" local.longcapture.MatcherTest
        Check-Exit 'Matcher tests'
    }
    & $aapt2 compile --dir "$stage/app/src/main/res" -o "$output/resources.zip"
    Check-Exit 'Compile resources'
    & $aapt2 link -o "$output/resources.apk" --manifest "$stage/app/src/main/AndroidManifest.xml" -I $stagedJar --java "$output/generated" --min-sdk-version 36 --target-sdk-version 36 --version-code 4 --version-name '0.1.3' "$output/resources.zip"
    Check-Exit 'Link resources'
    $sources = @(Get-ChildItem -LiteralPath "$stage/app/src/main/java", "$output/generated" -Recurse -Filter '*.java' | ForEach-Object FullName)
    $responseFile = Join-Path $output 'javac.args'
    $arguments = @('-encoding', 'UTF-8', '--release', '17', '-classpath', ('"' + $stagedJar + '"'), '-d', ('"' + "$output/classes" + '"')) + @($sources | ForEach-Object { '"' + $_ + '"' })
    [System.IO.File]::WriteAllLines($responseFile, @($arguments | ForEach-Object { $_.Replace('\', '/') }), [System.Text.UTF8Encoding]::new($false))
    & $javac "@$responseFile"
    Check-Exit 'Compile Java'
    & $jar --create --file "$output/classes.jar" -C "$output/classes" .
    Check-Exit 'Package classes'
    & $java -cp "$BuildTools/lib/d8.jar" com.android.tools.r8.D8 --lib $stagedJar --min-api 36 --output "$output/dex" "$output/classes.jar"
    Check-Exit 'DEX'
    Copy-Item -LiteralPath "$output/resources.apk" -Destination "$output/unsigned.apk"
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::Open("$output/unsigned.apk", [System.IO.Compression.ZipArchiveMode]::Update)
    try {
        foreach ($dex in Get-ChildItem -LiteralPath "$output/dex" -Filter '*.dex') {
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $dex.FullName, $dex.Name, [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
        }
    } finally { $archive.Dispose() }
    & $zipalign -f 4 "$output/unsigned.apk" "$output/aligned.apk"
    Check-Exit 'Align'
    if (-not (Test-Path -LiteralPath $KeystorePath)) {
        & $keytool -genkeypair -keystore $KeystorePath -storepass:env LONGPAGE_STORE_PASSWORD -keypass:env LONGPAGE_KEY_PASSWORD -alias $KeyAlias -dname 'CN=LongCapture Local Debug' -keyalg RSA -keysize 2048 -validity 10000
        Check-Exit 'Create development signing key'
    }
    & $java -jar "$BuildTools/lib/apksigner.jar" sign --ks $KeystorePath --ks-key-alias $KeyAlias --ks-pass env:LONGPAGE_STORE_PASSWORD --key-pass env:LONGPAGE_KEY_PASSWORD --out "$output/signed.apk" "$output/aligned.apk"
    Check-Exit 'Sign'
    & $java -jar "$BuildTools/lib/apksigner.jar" verify --verbose "$output/signed.apk"
    Check-Exit 'Verify signature'
    $buildDirectory = Join-Path $projectRoot 'build'
    New-Item -ItemType Directory -Path $buildDirectory -Force | Out-Null
    $apkName = if ($Release) { 'LongCapture.apk' } else { 'LongCapture-debug.apk' }
    $apkPath = Join-Path $buildDirectory $apkName
    Copy-Item -LiteralPath "$output/signed.apk" -Destination $apkPath -Force
    if ($Install) { & $AdbPath -d install -r $apkPath; Check-Exit 'Install' }
    Write-Output "APK: $apkPath"
} finally {
    $env:LONGPAGE_STORE_PASSWORD = $oldStorePassword
    $env:LONGPAGE_KEY_PASSWORD = $oldKeyPassword
    # Validate both parent and generated name before recursively deleting our per-run staging directory.
    $stageFull = [System.IO.Path]::GetFullPath($stage)
    $expectedParent = [System.IO.Path]::GetFullPath($workFull).TrimEnd([System.IO.Path]::DirectorySeparatorChar)
    if (([System.IO.Path]::GetDirectoryName($stageFull)).TrimEnd([System.IO.Path]::DirectorySeparatorChar) -ne $expectedParent -or
        [System.IO.Path]::GetFileName($stageFull) -notmatch '^longpage-[0-9a-f]{32}$') { throw 'Refusing unexpected work directory cleanup' }
    if (Test-Path -LiteralPath $stageFull) { Remove-Item -LiteralPath $stageFull -Recurse -Force }
}
