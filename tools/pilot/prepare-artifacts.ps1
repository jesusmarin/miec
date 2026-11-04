param(
    [string]$MavenExecutable = '',
    [string]$LocalRepository = '',
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
$miecRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
$petclinicRoot = [IO.Path]::GetFullPath((Join-Path $miecRoot '../spring-petclinic'))

# Compile first, then package sanitized build resources without copying local secrets again.
# Source properties and their working-tree credentials remain untouched.
foreach ($project in @(
    @{ Root = $miecRoot; Profile = 'application-postgres.properties'; Placeholder = '${MIEC_POSTGRES_PASSWORD:xxx}' },
    @{ Root = $petclinicRoot; Profile = 'application-miec-postgres.properties'; Placeholder = '${MIEC_PETCLINIC_POSTGRES_PASSWORD:xxx}' }
)) {
    $executable = if ($MavenExecutable) { $MavenExecutable } else { Join-Path $project.Root 'mvnw.cmd' }
    $common = @('--batch-mode', '--no-transfer-progress', '-Daether.syncContext.named.factory=rwlock-local')
    if ($Offline) { $common += '-o' }
    if ($LocalRepository) { $common += '-Dmaven.repo.local=' + $LocalRepository }
    Push-Location $project.Root
    try {
        & $executable @common compile
        if ($LASTEXITCODE -ne 0) { throw 'Compilation failed; pilot packaging stopped.' }
        $resource = Join-Path $project.Root ('target/classes/' + $project.Profile)
        $content = [IO.File]::ReadAllText($resource)
        $passwordPlaceholder = $project.Placeholder
        $pattern = '(?m)^spring.datasource.password=.*$'
        if ([regex]::Matches($content, $pattern).Count -ne 1) { throw 'Expected one database password setting.' }
        $sanitized = [regex]::Replace($content, $pattern, [Text.RegularExpressions.MatchEvaluator]{
            param($match)
            'spring.datasource.password=' + $passwordPlaceholder
        })
        [IO.File]::WriteAllText($resource, $sanitized, [Text.UTF8Encoding]::new($false))
        & $executable @common '-Dmaven.jar.forceCreation=true' 'jar:jar' 'spring-boot:repackage'
        if ($LASTEXITCODE -ne 0) { throw 'Sanitized JAR packaging failed.' }
        Write-Output ('PILOT ARTIFACT READY: ' + $project.Root + '; database password supplied only through environment.')
    }
    finally { Pop-Location }
}
