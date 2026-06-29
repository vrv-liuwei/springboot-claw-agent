param(
  [Parameter(Mandatory = $true)]
  [string]$CliDir
)

$resolvedCliDir = [System.IO.Path]::GetFullPath($CliDir).TrimEnd('\')
$path = [Environment]::GetEnvironmentVariable('Path', 'User')

if ([string]::IsNullOrWhiteSpace($path)) {
  [Environment]::SetEnvironmentVariable('Path', $resolvedCliDir, 'User')
  exit 0
}

$entries = $path -split ';' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
$exists = $entries | Where-Object {
  try {
    [System.IO.Path]::GetFullPath($_).TrimEnd('\') -ieq $resolvedCliDir
  } catch {
    $_.TrimEnd('\') -ieq $resolvedCliDir
  }
}

if (-not $exists) {
  $entries += $resolvedCliDir
  [Environment]::SetEnvironmentVariable('Path', ($entries -join ';'), 'User')
}
