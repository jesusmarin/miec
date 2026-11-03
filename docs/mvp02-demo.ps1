param(
    [Parameter(Mandatory=$true)][pscredential]$AuthorCredential,
    [Parameter(Mandatory=$true)][pscredential]$ApproverCredential,
    [string]$BaseUrl = 'http://127.0.0.1:8081',
    [ValidateRange(1,20)][int]$Actions = 1,
    [switch]$Reject
)
$ErrorActionPreference = 'Stop'

# Cada ejecucion crea recursos nuevos. No se imprimen claves ni cabeceras Basic.
function Invoke-Mvp02 {
    param([string]$Method,[string]$Path,[object]$Payload,[pscredential]$Credential)
    $headers = @{}
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

$system = Invoke-Mvp02 'Post' '/api/systems' @{ name='MVP02 demo'; ownerReference=$AuthorCredential.UserName } $AuthorCredential
$cycle = Invoke-Mvp02 'Post' "/api/systems/$($system.systemId)/cycles" @{} $AuthorCredential
$base = "/api/cycles/$($cycle.cycleId)"
$started = Invoke-Mvp02 'Post' "$base/start" @{ expectedVersion=$cycle.version; reason='Iniciar ciclo' } $AuthorCredential
$objective = Invoke-Mvp02 'Post' "$base/initialize" @{ expectedVersion=$started.version; objective='Reducir fallos'; successCriteria='Prueba sin errores'; reason='Definir objetivo' } $AuthorCredential
$evidence = Invoke-Mvp02 'Post' "$base/observations" @{ expectedVersion=$objective.cycle.version; description='Fallo observado'; reason='Registrar observacion' } $AuthorCredential
$diagnosis = Invoke-Mvp02 'Post' "$base/diagnosis" @{ expectedVersion=$evidence.cycle.version; evidenceId=$evidence.resourceId; summary='Ajustar proceso'; reason='Diagnostico humano' } $AuthorCredential
$actionList = @(1..$Actions | ForEach-Object { @{ actionType='PROCESS_EXECUTION'; description="Accion $_" } })
$plan = Invoke-Mvp02 'Post' "$base/plans" @{ expectedVersion=$diagnosis.cycle.version; objectiveId=$objective.resourceId; expectedOutcomes='Prueba satisfactoria'; rollbackStrategy='Restaurar configuracion'; actions=$actionList } $AuthorCredential
$planPath = "/api/plans/$($plan.planId)"
$requests = @()
foreach($action in $plan.actions) {
    $current = Invoke-Mvp02 'Get' $planPath $null $AuthorCredential
    $requests += Invoke-Mvp02 'Post' "$planPath/authorization-requests" @{ expectedPlanVersion=$current.version; actionId=$action.actionId; validForSeconds=3600 } $AuthorCredential
}
$reviewContext = Invoke-Mvp02 'Get' "$planPath/context" $null $ApproverCredential
Write-Output "Objetivo a revisar: $($reviewContext.objective.name)"
Write-Output "Diagnostico: $($reviewContext.diagnosis.summary)"
for($index=0; $index -lt $requests.Count; $index++) {
    $operation = if($Reject -and $index -eq $requests.Count-1){'reject'}else{'approve'}
    $decision = Invoke-Mvp02 'Post' "/api/authorizations/$($requests[$index].authorizationId)/$operation" @{ expectedVersion=$requests[$index].version; reason='Revision humana de demostracion' } $ApproverCredential
    Write-Output "Decision $operation : $($decision.status)"
}
$finalPlan = Invoke-Mvp02 'Get' $planPath $null $ApproverCredential
$finalCycle = Invoke-Mvp02 'Get' $base $null $AuthorCredential
[pscustomobject]@{ SystemId=$system.systemId; CycleId=$cycle.cycleId; PlanId=$plan.planId; PlanStatus=$finalPlan.status; CycleState=$finalCycle.state }
