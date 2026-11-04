<#
.SYNOPSIS
Valida sin interaccion la configuracion local usando exclusivamente secretos ficticios.
.DESCRIPTION
Ejecutar en un proceso PowerShell hijo, nunca dot-source en una consola operativa.
Comprueba aceptacion, rechazos atomicos y limpieza sin afectar otras variables.
#>
$ErrorActionPreference = 'Stop'
$helper = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../Set-LocalEnvironment.ps1'))
# Sustituye el dialogo de cuentas con valores ficticios por escenario.
function Get-Credential {
    param($UserName, $Message)
    if ($global:OpsValidationScenario -eq 'cancel' -and $UserName -eq 'postgres') { return $null }
    $name=$UserName
    if ($global:OpsValidationScenario -eq 'same-user' -and $name -eq 'approver') { $name='author' }
    if ($global:OpsValidationScenario -eq 'invalid-user' -and $name -eq 'author') { $name='invalid user' }
    $secret = 'test-only-' + $UserName + '-not-a-real-secret'
    if ($global:OpsValidationScenario -eq 'same-password' -and $UserName -in @('author','approver')) { $secret='test-only-shared-password' }
    [pscredential]::new($name,(ConvertTo-SecureString $secret -AsPlainText -Force))
}
# Sustituye exclusivamente el secreto del destinatario, sin abrir consola interactiva.
function Read-Host {
    param($Prompt, [switch]$AsSecureString)
    $secret=if ($global:OpsValidationScenario -eq 'short-key') {'short'} else {'test-only-recipient-api-key'}
    ConvertTo-SecureString $secret -AsPlainText -Force
}
$passed=0
foreach ($global:OpsValidationScenario in @('valid','same-user','same-password','invalid-user','short-key','cancel')) {
    & $helper -Clear | Out-Null
    $env:MIEC_AUTHOR_PASSWORD='test-only-existing-sentinel'
    $failed=$false
    try { & $helper | Out-Null } catch { $failed=$true }
    if ($global:OpsValidationScenario -eq 'valid') {
        if ($failed -or $env:MIEC_RECIPIENT_API_KEY -cne $env:MIEC_PETCLINIC_API_KEY -or $env:MIEC_POSTGRES_PASSWORD -cne $env:MIEC_PETCLINIC_POSTGRES_PASSWORD -or $env:MIEC_RECIPIENT_EXECUTE_PATH -ne '/miec/visits') { throw 'Valid configuration failed' }
    } else {
        if (-not $failed -or $env:MIEC_AUTHOR_PASSWORD -cne 'test-only-existing-sentinel' -or $env:MIEC_RECIPIENT_API_KEY) { throw 'Rejected configuration changed environment' }
    }
    $passed++
    Write-Output ('PASS ' + $global:OpsValidationScenario)
}
$env:MIEC_OPERATIONS_TEST_UNRELATED='test-only-preserve'
& $helper -Clear | Out-Null
if ($env:MIEC_AUTHOR_PASSWORD -or $env:MIEC_POSTGRES_PASSWORD -or $env:MIEC_OPERATIONS_TEST_UNRELATED -ne 'test-only-preserve') { throw 'Clear failed' }
$passed++
Write-Output ('PASSED: ' + $passed + '; no real credentials used')
