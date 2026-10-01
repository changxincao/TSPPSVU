param([Parameter(Mandatory=$true)][string]$TaskRoot,[Parameter(Mandatory=$true)][string]$ExperimentRoot,
      [long]$FirstSeed=20261025,[ValidateRange(1,100)][int]$ReplicationCount=5,[switch]$DryRun)
$ErrorActionPreference='Stop'
if(Test-Path -LiteralPath $ExperimentRoot){
    $existingInputs=Get-ChildItem -LiteralPath $ExperimentRoot -Directory -ErrorAction SilentlyContinue |
        Where-Object {$_.Name -like 'cv*'} | Select-Object -First 1
    if($existingInputs -or (Test-Path -LiteralPath (Join-Path $ExperimentRoot 'inputs_complete.txt'))){
        throw "Refusing to overwrite existing experiment inputs: $ExperimentRoot"
    }
}
$java=Join-Path $TaskRoot 'runtime\java\bin\java.exe';$cp="$TaskRoot\bin;E:\EnglishSave\Cplex22\cplex\lib\cplex.jar;$TaskRoot\lib\mosek.jar"
$cells=@(@('cv030050',.3,.5),@('cv040060',.4,.6),@('cv010030',.1,.3),@('cv050070',.5,.7))
if($DryRun){foreach($cell in $cells){[pscustomobject]@{cell=$cell[0];cv_lower=$cell[1];cv_upper=$cell[2];first_seed=$FirstSeed;count=$ReplicationCount;target=(Join-Path $ExperimentRoot "$($cell[0])\input")}};return}
New-Item -ItemType Directory -Force -Path $ExperimentRoot|Out-Null
foreach($cell in $cells){$input=Join-Path $ExperimentRoot "$($cell[0])\input";& $java -Xmx2g -cp $cp Test.analysis.synthetic.TRBSVUGeneratePairedCvCasesMain $input $cell[1] $cell[2] $FirstSeed $ReplicationCount *> (Join-Path $ExperimentRoot "generation_$($cell[0]).log");if($LASTEXITCODE-ne0){throw "Generation failed: $($cell[0])"}}
$audit=@();foreach($cell in $cells){for($r=0;$r-lt$ReplicationCount;$r++){$rep='rep_{0:D3}'-f$r;$root=Join-Path $ExperimentRoot "$($cell[0])\input\$rep";$m=Get-Content "$root\instance\manifest.txt";if($m-notcontains"caseSeed=$([long]$FirstSeed+$r)"-or$m-notcontains"cvLower=$($cell[1])"-or$m-notcontains"cvUpper=$($cell[2])"-or$m-notcontains'queries=40'-or$m-notcontains'OOS=1000'-or$m-notcontains'selectionUpperCount=12'){throw "Manifest mismatch $($cell[0])/$rep"};$d=@(Import-Csv "$root\instance\dgp_parameters.csv");if($d.Count-ne50-or@($d|?{[double]$_.cv-lt$cell[1]-or[double]$_.cv-gt$cell[2]}).Count){throw "CV mismatch $($cell[0])/$rep"};$q=@(Import-Csv "$root\queries\queries.tsv" -Delimiter "`t");if($q.Count-ne40-or@($q|?{$_.query_type-ne'RANDOM'}).Count){throw "Query mismatch $($cell[0])/$rep"};foreach($i in 0..39){$f=Join-Path $root ('queries\query_{0:D3}.instance.tsv'-f$i);if(@(Select-String $f -Pattern '^SAMPLE\tOOS\t').Count-ne1000){throw "OOS mismatch $f"}};$audit+=[pscustomobject]@{cell=$cell[0];rep=$rep;case_seed=$FirstSeed+$r;cv_lower=$cell[1];cv_upper=$cell[2];queries=40;oos=1000}}}
for($r=0;$r-lt$ReplicationCount;$r++){$rep='rep_{0:D3}'-f$r;$a=Join-Path $ExperimentRoot "cv030050\input\$rep";foreach($cell in $cells|?{$_[0]-ne'cv030050'}){$b=Join-Path $ExperimentRoot "$($cell[0])\input\$rep";foreach($f in @('instance\carriers.csv','instance\carrier_lane.csv','instance\lanes.csv','queries\queries.tsv')){if((Get-FileHash (Join-Path $a $f)).Hash-ne(Get-FileHash (Join-Path $b $f)).Hash){throw "Pair mismatch $rep $($cell[0]) $f"}}}}
$audit|Export-Csv (Join-Path $ExperimentRoot 'input_audit.csv') -NoTypeInformation -Encoding UTF8
@("seeds=$FirstSeed..$($FirstSeed+$ReplicationCount-1)",'cells=cv030050,cv040060,cv010030,cv050070','methods=D,SAA-All,CSAA-Exp,CSAA-Tri,RF-CSAA','dro_reference_selection=OOS Mean best among CSAA-Exp,CSAA-Tri,RF-CSAA','dro_reference_selection_label=exploratory_OOS_selected_not_training_selected')|Set-Content (Join-Path $ExperimentRoot 'protocol.txt') -Encoding UTF8
Set-Content (Join-Path $ExperimentRoot 'inputs_complete.txt') ([DateTime]::Now.ToString('o')) -Encoding UTF8
