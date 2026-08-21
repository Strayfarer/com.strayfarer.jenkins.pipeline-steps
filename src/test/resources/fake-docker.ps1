$DockerArgs = $args

$log = $env:FAKE_DOCKER_LOG
if (-not $log) {
    $log = Join-Path (Split-Path -Parent $PSScriptRoot) 'docker.log'
}
Add-Content -LiteralPath $log -Value ('ARGS|' + ($DockerArgs -join '|'))

if ($DockerArgs[0] -eq 'inspect') {
    $container = $DockerArgs[-1]
    switch ($container) {
        'missing' { exit 1 }
        'stopped' { Write-Output 'id-stopped false windows'; exit 0 }
        'unsupported' { Write-Output 'id-unsupported true plan9'; exit 0 }
        default { Write-Output "id-$container true windows"; exit 0 }
    }
}

if ($DockerArgs[0] -ne 'exec') {
    exit 2
}

$index = 1
while ($index -lt $DockerArgs.Count) {
    if ($DockerArgs[$index] -eq '--workdir') {
        $index += 2
    } elseif ($DockerArgs[$index] -eq '--env') {
        $name = $DockerArgs[$index + 1]
        $value = [Environment]::GetEnvironmentVariable($name)
        Add-Content -LiteralPath $log -Value "ENV|$name|$value"
        $index += 2
    } elseif ($DockerArgs[$index] -eq '--') {
        $index++
        break
    } else {
        $index++
    }
}

$container = $DockerArgs[$index]
$index++
Add-Content -LiteralPath $log -Value "EXEC|$container"
$command = $DockerArgs[$index]
$commandArguments = if ($index + 1 -lt $DockerArgs.Count) {
    $DockerArgs[($index + 1)..($DockerArgs.Count - 1)]
} else {
    @()
}
if ($command -eq 'pwsh') {
    for ($argumentIndex = 0; $argumentIndex -lt $commandArguments.Count - 1; $argumentIndex++) {
        if ($commandArguments[$argumentIndex] -eq '-EncodedCommand') {
            $decoded = [Text.Encoding]::Unicode.GetString(
                [Convert]::FromBase64String($commandArguments[$argumentIndex + 1]))
            if ($decoded.Contains('taskkill.exe')) {
                Add-Content -LiteralPath $log -Value "KILL|$container"
                exit 0
            }
        }
    }
    if (-not (Get-Command pwsh -ErrorAction SilentlyContinue)) {
        $command = 'powershell'
    }
}
& $command @commandArguments
exit $LASTEXITCODE
