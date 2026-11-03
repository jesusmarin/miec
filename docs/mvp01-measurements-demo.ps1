param(
    [Parameter(Mandatory=$true)][pscredential]$AuthorCredential,
    [string]$BaseUrl = 'http://127.0.0.1:8081'
)
$ErrorActionPreference = 'Stop'

# Cada ejecucion crea recursos nuevos. No se imprimen claves ni cabeceras Basic.
function Invoke-Mvp02 {
    param([string]$Method,[string]$Path,[object]$Payload,[pscredential]$Credential)
    $headers = @{ 'X-MIEC-Actor' = $AuthorCredential.UserName }
    if($null -ne $Credential) {
        $pair = $Credential.UserName + ':' + $Credential.GetNetworkCredential().Password
        $headers.Authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($pair))
    }
    $arguments = @{ Method=$Method; Uri=($BaseUrl.TrimEnd('/')+$Path); Headers=$headers }
    if($Method -eq 'Post') {
        $arguments.ContentType = 'application/json; charset=utf-8'
        $arguments.Body = [Text.Encoding]::UTF8.GetBytes(($Payload | ConvertTo-Json -Depth 8 -Compress))
    }
    Invoke-RestMethod @arguments
}

$system = Invoke-Mvp02 'Post' '/api/systems' @{ name='MVP v01 mediciones' } $null
$cycle = Invoke-Mvp02 'Post' "/api/systems/$($system.systemId)/cycles" @{} $null
$base = "/api/cycles/$($cycle.cycleId)"
$started = Invoke-Mvp02 'Post' "$base/start" @{ expectedVersion=$cycle.version; reason='Iniciar' } $null
$objective = Invoke-Mvp02 'Post' "$base/measurement/initialize" @{
    expectedVersion=$started.version; objective='Reducir latencia'; successCriteria='p95 <= 100 ms'; reason='Objetivo medible'
    criteria=@(@{ name='p95'; unit='ms'; operator='LTE'; target=100 })
} $AuthorCredential
Write-Output "Objetivo persistido; ciclo en $($objective.cycle.state)"
$context = Invoke-Mvp02 'Get' "$base/measurement" $null $AuthorCredential
$measurement = Invoke-Mvp02 'Post' "$base/measurement/baselines" @{
    expectedVersion=$objective.cycle.version; indicatorId=$context.objectives[0].criteria[0].indicatorId
    value=125.5; unit='ms'; source='Reporte manual de laboratorio'
    measuredAt=[DateTimeOffset]::UtcNow.AddSeconds(-5).ToString('o'); reason='Registrar linea base'
} $AuthorCredential
if($measurement.cycle.state -ne 'E02_OBSERVANDO') { throw 'La medicion no debe cerrar observacion' }
Write-Output 'Linea base registrada; E02 conservado.'
$closed = Invoke-Mvp02 'Post' "$base/measurement/complete" @{ expectedVersion=$measurement.cycle.version; reason='Todas las lineas base completas' } $AuthorCredential
if($closed.cycle.state -ne 'E03_DIAGNOSTICANDO') { throw 'Cierre sin transicion esperada' }
$finalContext = Invoke-Mvp02 'Get' "$base/measurement" $null $AuthorCredential
if($finalContext.baselines.Count -ne 1) { throw 'Linea base no consultable' }
Write-Output 'DEMOSTRACION COMPLETA: sistema, ciclo, objetivo medible, linea base y cierre auditado.'
[pscustomobject]@{ SystemId=$system.systemId; CycleId=$cycle.cycleId; ObjectiveId=$objective.resourceId; EvidenceId=$closed.resourceId; State=$closed.cycle.state }
