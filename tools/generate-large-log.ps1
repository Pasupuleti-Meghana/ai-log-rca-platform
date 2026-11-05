# Generates a large multi-service log file for performance demos (default: 1,000,000 lines).
# Usage: .\tools\generate-large-log.ps1 [-Output target\large.log] [-Lines 1000000]
param(
    [string]$Output = "target\large.log",
    [long]$Lines = 1000000
)
$root = Split-Path -Parent $PSScriptRoot
java "$root\src\test\java\com\logsense\tools\LogGenerator.java" $Output $Lines