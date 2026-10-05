$ErrorActionPreference='Stop'
$script=Join-Path $PSScriptRoot 'run_three_experiments_remote.ps1'
$tokens=$null;$errors=$null
$ast=[System.Management.Automation.Language.Parser]::ParseFile($script,[ref]$tokens,[ref]$errors)
if($errors.Count){throw ($errors|Out-String)}
foreach($name in @('HasFile','Complete')){
    $function=$ast.Find({param($node)$node-is[System.Management.Automation.Language.FunctionDefinitionAst]-and$node.Name-eq$name},$true)
    if($null-eq$function){throw "Missing $name"}
    Invoke-Expression $function.Extent.Text
}
$scratch=Join-Path ([IO.Path]::GetTempPath()) ('tspp_completion_'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $scratch|Out-Null
$config=Join-Path $scratch 'config.properties';$csv=Join-Path $scratch 'comparison.csv'
$job=[pscustomobject]@{kind='comparison';output=$scratch;config=$config}
function WriteFixture($grid,$rows){
    "lambdaGrid=$grid"|Set-Content -LiteralPath $config
    @($rows|ForEach-Object{[pscustomobject]@{lambda=$_}})|Export-Csv -LiteralPath $csv -NoTypeInformation
}
'saved'|Set-Content -LiteralPath (Join-Path $scratch 'complete.txt')
WriteFixture '0.01,0.05,0.1,0.25,0.5,1,2,5,10,50,100' @(0.01,0.05,0.1,0.25,0.5,1,2,5,10,50,100)
if(-not(Complete $job)){throw 'Original eleven-lambda completion rejected'}
WriteFixture '0.001,0.005' @(0.001,0.005)
if(-not(Complete $job)){throw 'Two-lambda supplement rejected'}
WriteFixture '0.001,0.005' @(0.001)
if(Complete $job){throw 'Partial supplement accepted'}
WriteFixture '0.001,0.005' @(0.001,0.001)
if(Complete $job){throw 'Duplicate lambda accepted'}
WriteFixture '0.001,0.005' @(0.001,0.01)
if(Complete $job){throw 'Wrong lambda accepted'}
WriteFixture '0.001,0.005' @(0.001,0.005)
# Only explicitly created fixture files are removed, not a recursive target.
Remove-Item -LiteralPath (Join-Path $scratch 'complete.txt')
if(Complete $job){throw 'Missing complete receipt accepted'}
Remove-Item -LiteralPath $config,$csv
Remove-Item -LiteralPath $scratch
Write-Output 'THREE_EXPERIMENTS_COMPLETION_SELF_CHECK_PASS'
