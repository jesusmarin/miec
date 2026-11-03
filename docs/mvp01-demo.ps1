param(
    [string]$BaseUrl = 'http://127.0.0.1:8081',
    [string]$Actor = 'mvp-operator'
)

$ErrorActionPreference = 'Stop'

# Ejecutar manualmente contra la instancia local que se desea utilizar.
# Cada ejecución crea un sistema y un ciclo nuevos.
function Send-MvpCommand {
    param([string]$Path, [object]$Payload)
    $jsonBody = $Payload | ConvertTo-Json -Compress
    Invoke-RestMethod -Method Post -Uri ($BaseUrl.TrimEnd('/') + $Path) `
        -ContentType 'application/json; charset=utf-8' `
        -Headers @{ 'X-MIEC-Actor' = $Actor } -Body ([Text.Encoding]::UTF8.GetBytes($jsonBody))
}

$system = Send-MvpCommand '/api/systems' @{ name = 'Sistema de demostración MVP'; domain = 'software' }
$cycle = Send-MvpCommand "/api/systems/$($system.systemId)/cycles" @{}
$started = Send-MvpCommand "/api/cycles/$($cycle.cycleId)/start" @{ expectedVersion = $cycle.version; reason = 'Inicio de demostración MVP' }
$history = Invoke-RestMethod -Method Get -Uri ($BaseUrl.TrimEnd('/') + "/api/cycles/$($cycle.cycleId)/history")

[pscustomobject]@{
    SystemId = $system.systemId
    CycleId = $cycle.cycleId
    State = $started.state
    Version = $started.version
    Transitions = @($history.items).Count
}
