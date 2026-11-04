<#
.SYNOPSIS
Configura las credenciales del laboratorio en el entorno del proceso actual.
.DESCRIPTION
Solicita secretos sin eco y configura MIEC y PetClinic sin escribir archivos.
Ejecutar desde la consola que iniciara las aplicaciones. No inicia servidores.
El modo Clear retira exclusivamente las variables administradas por este script.
#>
[CmdletBinding()]
param([switch]$Clear)

$ErrorActionPreference = 'Stop'
$managed = @(
    'MIEC_POSTGRES_URL', 'MIEC_POSTGRES_USERNAME', 'MIEC_POSTGRES_PASSWORD',
    'MIEC_PETCLINIC_POSTGRES_URL', 'MIEC_PETCLINIC_POSTGRES_USERNAME', 'MIEC_PETCLINIC_POSTGRES_PASSWORD',
    'MIEC_AUTHOR_USERNAME', 'MIEC_AUTHOR_PASSWORD', 'MIEC_APPROVER_USERNAME', 'MIEC_APPROVER_PASSWORD',
    'MIEC_PETCLINIC_API_KEY', 'MIEC_PETCLINIC_DESTINATION',
    'MIEC_RECIPIENT_ENABLED', 'MIEC_RECIPIENT_ADAPTER_ID', 'MIEC_RECIPIENT_ADAPTER_VERSION',
    'MIEC_RECIPIENT_DESTINATION_ID', 'MIEC_RECIPIENT_BASE_URL', 'MIEC_RECIPIENT_API_KEY',
    'MIEC_RECIPIENT_EXECUTE_PATH', 'MIEC_RECIPIENT_TIMEOUT'
)
if ($Clear) {
    foreach ($name in $managed) { [Environment]::SetEnvironmentVariable($name, $null, 'Process') }
    Write-Output 'Entorno local retirado de esta consola; los procesos ya iniciados conservan su propia copia.'
    return
}

# Recoger y validar antes de modificar el entorno evita configuraciones parciales.
$database = Get-Credential -UserName 'postgres' -Message 'PostgreSQL local: ambas bases usan esta misma cuenta'
$author = Get-Credential -UserName 'author' -Message 'MIEC AUTHOR: clave de al menos 12 caracteres'
$approver = Get-Credential -UserName 'approver' -Message 'MIEC APPROVER: identidad y clave diferentes del autor'
$recipient = Read-Host 'API key compartida MIEC/PetClinic (al menos 16 caracteres)' -AsSecureString
if ($null -eq $database -or $null -eq $author -or $null -eq $approver -or $null -eq $recipient) {
    throw 'Configuracion cancelada; no se modifico el entorno.'
}
$values = @{}
try {
    $dbPassword = $database.GetNetworkCredential().Password
    $authorPassword = $author.GetNetworkCredential().Password
    $approverPassword = $approver.GetNetworkCredential().Password
    $apiKey = [System.Net.NetworkCredential]::new('', $recipient).Password
    if ([string]::IsNullOrWhiteSpace($dbPassword)) { throw 'La cuenta PostgreSQL requiere clave.' }
    foreach ($account in @($author, $approver)) {
        if ($account.UserName -notmatch '^[A-Za-z0-9._-]{1,100}$' -or
            [string]::IsNullOrWhiteSpace($account.GetNetworkCredential().Password) -or $account.Password.Length -lt 12) {
            throw 'Identidad MIEC invalida o clave menor de 12 caracteres.'
        }
    }
    if ($author.UserName -ieq $approver.UserName -or $authorPassword -ceq $approverPassword) {
        throw 'AUTHOR y APPROVER requieren identidades y claves diferentes.'
    }
    if ([string]::IsNullOrWhiteSpace($apiKey) -or $apiKey.Length -lt 16) { throw 'API key invalida o menor de 16 caracteres.' }
    $values = @{
        MIEC_POSTGRES_URL = 'jdbc:postgresql://127.0.0.1:5532/miec-v01?currentSchema=public'
        MIEC_POSTGRES_USERNAME = $database.UserName; MIEC_POSTGRES_PASSWORD = $dbPassword
        MIEC_PETCLINIC_POSTGRES_URL = 'jdbc:postgresql://127.0.0.1:5532/petclinic?currentSchema=public'
        MIEC_PETCLINIC_POSTGRES_USERNAME = $database.UserName; MIEC_PETCLINIC_POSTGRES_PASSWORD = $dbPassword
        MIEC_AUTHOR_USERNAME = $author.UserName; MIEC_AUTHOR_PASSWORD = $authorPassword
        MIEC_APPROVER_USERNAME = $approver.UserName; MIEC_APPROVER_PASSWORD = $approverPassword
        MIEC_PETCLINIC_API_KEY = $apiKey; MIEC_PETCLINIC_DESTINATION = 'petclinic-experiment-001'
        MIEC_RECIPIENT_ENABLED = 'true'; MIEC_RECIPIENT_ADAPTER_ID = 'http-recipient-v1'
        MIEC_RECIPIENT_ADAPTER_VERSION = '1'; MIEC_RECIPIENT_DESTINATION_ID = 'petclinic-experiment-001'
        MIEC_RECIPIENT_BASE_URL = 'http://127.0.0.1:8082'; MIEC_RECIPIENT_API_KEY = $apiKey
        MIEC_RECIPIENT_EXECUTE_PATH = '/miec/visits'; MIEC_RECIPIENT_TIMEOUT = 'PT10S'
    }
    foreach ($name in $managed) { [Environment]::SetEnvironmentVariable($name, $values[$name], 'Process') }
    Write-Output 'Entorno local configurado: PostgreSQL 5532, MIEC 8081, PetClinic 8082. No se guardaron secretos en archivos.'
}
finally {
    # Retira referencias temporales; .NET no garantiza el borrado de cadenas en memoria.
    $values.Clear()
    $dbPassword = $authorPassword = $approverPassword = $apiKey = $null
    $database = $author = $approver = $recipient = $null
}
