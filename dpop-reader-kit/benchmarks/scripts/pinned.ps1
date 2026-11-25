# Lance une commande épinglée sur un ensemble de processeurs logiques (Windows).
#   powershell -File pinned.ps1 -Mask FFC00 -Log out.txt -Exe java.exe -ArgLine "-jar x.jar ..."
# L'affinité est fixée dès le démarrage ; les processus enfants (forks JMH, processus Chromium)
# en héritent, puisqu'ils sont créés après.
param(
  [Parameter(Mandatory = $true)][string]$Mask,
  [Parameter(Mandatory = $true)][string]$Log,
  [Parameter(Mandatory = $true)][string]$Exe,
  [Parameter(Mandatory = $true)][string]$ArgLine,
  [string]$WorkDir = (Get-Location).Path
)
$p = Start-Process -FilePath $Exe -ArgumentList $ArgLine -WorkingDirectory $WorkDir -NoNewWindow -PassThru `
  -RedirectStandardOutput $Log -RedirectStandardError "$Log.stderr"
$p.ProcessorAffinity = [IntPtr][Convert]::ToInt64($Mask, 16)
$p.WaitForExit()
exit $p.ExitCode
