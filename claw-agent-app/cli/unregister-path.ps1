param(
  [Parameter(Mandatory = $true)]
  [string]$CliDir
)

$resolvedCliDir = [System.IO.Path]::GetFullPath($CliDir).TrimEnd('\')
$path = [Environment]::GetEnvironmentVariable('Path', 'User')

if ([string]::IsNullOrWhiteSpace($path)) {
  exit 0
}

$entries = $path -split ';' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
$next = $entries | Where-Object {
  try {
    [System.IO.Path]::GetFullPath($_).TrimEnd('\') -ine $resolvedCliDir
  } catch {
    $_.TrimEnd('\') -ine $resolvedCliDir
  }
}

[Environment]::SetEnvironmentVariable('Path', ($next -join ';'), 'User')
