<#
.SYNOPSIS
Prepara un plan ejecutable con objetivo medible y permisos; -Execute solicita el efecto externo.
.DESCRIPTION
Guion de laboratorio. Requiere un destinatario compatible configurado en MIEC y
un Body de negocio válido. Sin -Execute termina en E06. Usa cuentas configuradas,
no imprime sus secretos y no sustituye una interfaz de revisión humana del contenido.
.EXAMPLE
$author = Get-Credential -UserName author
$approver = Get-Credential -UserName approver
.\docs\mvp03-demo.ps1 -AuthorCredential $author -ApproverCredential $approver -Body '{"change":1}'
#>
param(
    [Parameter(Mandatory=$true)][pscredential]$AuthorCredential,
    [Parameter(Mandatory=$true)][pscredential]$ApproverCredential,
    [Parameter(Mandatory=$true)][string]$Body,
    [string]$BaseUrl = 'http://127.0.0.1:8081',
    [string]$AdapterId = 'http-recipient-v1',
    [switch]$Execute
)
$ErrorActionPreference = 'Stop'

# Envía comandos con identidad real; no acepta una cabecera de actor ni imprime Authorization.
function Invoke-Mvp03 {
    param([string]$Method,[string]$Path,[object]$Payload,[pscredential]$Credential)
    $pair = $Credential.UserName + ':' + $Credential.GetNetworkCredential().Password
    $headers = @{ Authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($pair)) }
    $arguments = @{ Method=$Method; Uri=($BaseUrl.TrimEnd('/')+$Path); Headers=$headers }
    if($Method -eq 'Post') {
        $arguments.ContentType = 'application/json; charset=utf-8'
        $arguments.Body = [Text.Encoding]::UTF8.GetBytes(($Payload | ConvertTo-Json -Depth 10 -Compress))
    }
    Invoke-RestMethod @arguments
}

# Descubre el enlace instalado antes de crear recursos; no instala adaptadores desde el cliente.
$catalog = @(Invoke-Mvp03 'Get' '/api/execution-adapters' $null $AuthorCredential)
$installed = @($catalog | Where-Object { $_.adapterId -eq $AdapterId })
if($installed.Count -ne 1) { throw 'El adaptador solicitado no esta instalado de forma unica.' }
$selected = $installed[0]
$Body | ConvertFrom-Json | Out-Null

$system = Invoke-Mvp03 'Post' '/api/systems' @{ name='MVP03 laboratorio' } $AuthorCredential
$cycle = Invoke-Mvp03 'Post' "/api/systems/$($system.systemId)/cycles" @{} $AuthorCredential
$cyclePath = "/api/cycles/$($cycle.cycleId)"
$started = Invoke-Mvp03 'Post' "$cyclePath/start" @{ expectedVersion=$cycle.version; reason='Iniciar laboratorio' } $AuthorCredential
$objective = Invoke-Mvp03 'Post' "$cyclePath/measurement/initialize" @{
    expectedVersion=$started.version; objective='Reducir errores'; successCriteria='Errores observados = 0'; reason='Objetivo medible'
    criteria=@(@{ name='errores'; unit='count'; operator='EQ'; target=0 })
} $AuthorCredential
$context = Invoke-Mvp03 'Get' "$cyclePath/measurement" $null $AuthorCredential
$baseline = Invoke-Mvp03 'Post' "$cyclePath/measurement/baselines" @{
    expectedVersion=$objective.cycle.version; indicatorId=$context.objectives[0].criteria[0].indicatorId
    value=1; unit='count'; source='Reporte manual de laboratorio'; measuredAt=[DateTimeOffset]::UtcNow.AddSeconds(-5).ToString('o'); reason='Linea base'
} $AuthorCredential
$closed = Invoke-Mvp03 'Post' "$cyclePath/measurement/complete" @{ expectedVersion=$baseline.cycle.version; reason='Observacion completa' } $AuthorCredential
$diagnosis = Invoke-Mvp03 'Post' "$cyclePath/diagnosis" @{ expectedVersion=$closed.cycle.version; evidenceId=$closed.resourceId; summary='Evaluar cambio controlado'; reason='Diagnostico' } $AuthorCredential
$definition = @{ adapterId=$selected.adapterId; adapterVersion=$selected.adapterVersion; destinationId=$selected.destinationId; mediaType='application/json'; body=$Body }
$plan = Invoke-Mvp03 'Post' "$cyclePath/plans" @{
    expectedVersion=$diagnosis.cycle.version; objectiveId=$objective.resourceId; expectedOutcomes='Cambio aplicado; medicion posterior pendiente'; rollbackStrategy='Revision humana ante fallos'
    actions=@(@{ actionType='PROCESS_EXECUTION'; description='Cambio de laboratorio'; execution=$definition })
} $AuthorCredential
$planPath = "/api/plans/$($plan.planId)"
$request = Invoke-Mvp03 'Post' "$planPath/authorization-requests" @{ expectedPlanVersion=$plan.version; actionId=$plan.actions[0].actionId; validForSeconds=3600 } $AuthorCredential
$review = Invoke-Mvp03 'Get' "$planPath/context" $null $ApproverCredential
Write-Output "Plan de laboratorio: $($plan.planId); objetivo: $($review.objective.name)"
$decision = Invoke-Mvp03 'Post' "/api/authorizations/$($request.authorizationId)/approve" @{ expectedVersion=$request.version; reason='Aprobacion explicita del ejercicio de laboratorio' } $ApproverCredential
if(-not $Execute) {
    Write-Output 'Plan preparado en E06; no se solicito ejecutar. Consultar y revisar el plan antes de decidir.'
    [pscustomobject]@{ CycleId=$cycle.cycleId; PlanId=$plan.planId; AuthorizationStatus=$decision.status }
    return
}

# Este punto solicita un efecto real al destinatario. Un timeout exige consultar el recurso, no reenviar.
$currentCycle = Invoke-Mvp03 'Get' $cyclePath $null $AuthorCredential
$currentPlan = Invoke-Mvp03 'Get' $planPath $null $AuthorCredential
$run = Invoke-Mvp03 'Post' "$cyclePath/plans/$($plan.planId)/execution" @{
    expectedCycleVersion=$currentCycle.version; expectedPlanVersion=$currentPlan.version; reason='Ejecutar contenido aprobado en laboratorio'
} $AuthorCredential
Write-Output "Coordinacion: $($run.state); ciclo: $($run.cycleState); requiere revision: $($run.requiresReview)"
Write-Output "Consulta durable: $planPath/execution"
$run
