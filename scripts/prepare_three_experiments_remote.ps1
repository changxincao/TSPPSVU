param([Parameter(Mandatory=$true)][ValidateSet('MAIN','SECONDARY')][string]$Mode,
      [Parameter(Mandatory=$true)][string]$Root,
      [Parameter(Mandatory=$true)][string]$BaseDeployment,
      [Parameter(Mandatory=$true)][string]$MainInput,
      [Parameter(Mandatory=$true)][string]$MainRf,
      [string]$NormalInputRoot='', [string]$LognormalInputRoot='', [string]$PythonOverride='')
$ErrorActionPreference='Stop';$ProgressPreference='SilentlyContinue'
$control=Join-Path $Root 'control';$deployment=Join-Path $Root 'deployment'
if(Test-Path -LiteralPath "$control\manifest.json"){throw 'Already prepared; do not overwrite frozen queue'}
New-Item -ItemType Directory -Force -Path "$control\logs","$deployment\lib"|Out-Null
Copy-Item -LiteralPath "$BaseDeployment\lib\mosek.jar" -Destination "$deployment\lib"
Get-ChildItem -LiteralPath "$BaseDeployment\lib" -Filter *.dll|Copy-Item -Destination "$deployment\lib"
$newHost=$Mode-eq'MAIN'
$java=if($newHost){"$BaseDeployment\runtime\java\bin\java.exe"}else{'D:\Java\jdk-21\bin\java.exe'}
$python=if($newHost){"$BaseDeployment\runtime\python\python.exe"}else{'D:\ccx\TSPP_SVU\deployments\e0040ef\.venv-rsome\Scripts\python.exe'}
if($PythonOverride){$python=$PythonOverride}
$cplex=if($newHost){'E:\EnglishSave\Cplex22\cplex'}else{'D:\software\IBM\ILOG\CPLEX_Studio2211\cplex'}
$license=if($newHost){"$BaseDeployment\tmp\mosek.lic"}else{'C:\Users\codex-runner\mosek\mosek.lic'}
foreach($p in @($java,$python,"$cplex\lib\cplex.jar",$license)){if(-not(Test-Path -LiteralPath $p)){throw "Missing runtime: $p"}}
$native="$cplex\bin\x64_win64;$deployment\lib"
$classpath="$deployment\bin;$cplex\lib\cplex.jar;$deployment\lib\mosek.jar"
$env:PATH="$native;$env:PATH";$env:MOSEKLM_LICENSE_FILE=$license
$env:OMP_NUM_THREADS='1';$env:MKL_NUM_THREADS='1';$env:OPENBLAS_NUM_THREADS='1'
$checks=@("import numpy, sklearn, rsome, mosek; print('PYTHON_DEPENDENCIES_OK', numpy.__version__, sklearn.__version__, mosek.Env.getversion())")
& $python -c $checks[0]
if($LASTEXITCODE-ne0){throw 'Python dependency preflight failed'}
Set-Location -LiteralPath $deployment
& $java -cp $classpath Test.analysis.synthetic.TRBSVUProtocolRegressionSelfCheck *> "$control\protocol_check.log"
if($LASTEXITCODE-ne0){throw 'Protocol self-check failed'}
& $java ("-Djava.library.path=$native") -cp $classpath Test.analysis.synthetic.TRBSVULambdaComparisonSelfCheck --native *> "$control\native_check.log"
if($LASTEXITCODE-ne0){throw 'CPLEX/MOSEK native self-check failed'}
& $java ("-Dtrb.svu.python=$python") -cp $classpath Test.analysis.synthetic.TRBSVUMomentRuntimeSelfCheck *> "$control\moment_check.log"
if($LASTEXITCODE-ne0){throw 'RSOME/MOSEK moment self-check failed'}
$jobs=[Collections.Generic.List[object]]::new()
function Job($id,$lane,$rank,$kind,$method,$rep,$caseInput,$choice,$output,$depends){
    $jobs.Add([pscustomobject]@{id=$id;lane=$lane;rank=$rank;kind=$kind;method=$method;rep=$rep;input=$caseInput;choice=$choice;output=$output;depends=@($depends);config='';weights=''})
}
$hashes=[Collections.Generic.List[object]]::new()
function RecordHash($p){$hashes.Add([pscustomobject]@{path=$p;sha256=(Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash})}
if($Mode-eq'MAIN'){
    $rank=0
    foreach($method in @('RCSAA','C-Chi2','C-W1','C-MM','C-PCM')){
        foreach($r in 1..5){
            $rep='rep_{0:D3}'-f$r
            $choice="$Root\frozen_rf\$rep\context_candidate.csv"
            New-Item -ItemType Directory -Force -Path (Split-Path $choice -Parent)|Out-Null
            Copy-Item -LiteralPath "$MainRf\$rep\RF-CSAA\queries\query_000\validation\context_candidate.csv" -Destination $choice
            RecordHash $choice
            Job "main_${rep}_$method" 'main' $rank 'robust' $method $r "$MainInput\$rep" $choice "$Root\results\$rep\$method" @()
            $manifest=Import-Csv -LiteralPath "$MainInput\$rep\queries\queries.tsv" -Delimiter "`t"
            if($manifest.Count-ne40-or@($manifest|Where-Object{$_.query_type-ne'RANDOM'}).Count){throw 'Wrong main query pool'}
            if($rank-eq0){foreach($q in $manifest){RecordHash (Join-Path "$MainInput\$rep\queries" $q.instance_file)}}
        };$rank++
    }
}else{
    # Both experiments share one four-slot controller; no result imports.
    foreach($r in 1..5){
        $rep='rep_{0:D3}'-f$r
        $choice=Import-Csv -LiteralPath "$MainRf\$rep\RF-CSAA\queries\query_000\validation\context_candidate.csv"
        if($choice.family-ne'RF'){throw 'Comparison center is not RF'}
        $queries=Import-Csv -LiteralPath "$MainInput\$rep\queries\queries.tsv" -Delimiter "`t"
        if($queries.Count-ne40){throw 'Wrong comparison query count'}
        foreach($q in $queries){
            $qn='query_{0:D3}'-f[int]$q.query_index
            $caseInput=Join-Path "$MainInput\$rep\queries" $q.instance_file
            $frozen="$Root\comparison\input\$rep\$qn"
            New-Item -ItemType Directory -Force -Path $frozen|Out-Null
            $weightSource="$MainRf\$rep\RF-CSAA\queries\$qn\solve\final_weights.csv"
            $rows=@(Import-Csv -LiteralPath $weightSource)
            if($rows.Count-ne75){throw "Expected 75 RF weights: $weightSource"}
            $weightFile="$frozen\weights.tsv"
            $lines=@("row_index`tsample_id`tweight")+@($rows|ForEach-Object{"$($_.row_index)`t$($_.sample_id)`t$($_.input_weight)"})
            [IO.File]::WriteAllLines($weightFile,$lines,[Text.UTF8Encoding]::new($false))
            $config="$frozen\config.properties"
            $configText=@('protocol=TRBSVU_LAMBDA_COMPARISON_V2','state=FROZEN',"caseId=$rep","queryId=$qn","replication=$r",'contextFamily=RF',"contextParameter=$($choice.validation_selected_min_leaf)","validationContextParameter=$($choice.validation_selected_min_leaf)","weightSource=$($weightSource.Replace('\','/'))",'solverThreads=4','timeLimit=UNLIMITED','lambdaGrid=0.01,0.05,0.1,0.25,0.5,1,2,5,10,50,100','reuseEvaluations=false',"runtimeFingerprint=$((Get-FileHash -LiteralPath "$deployment\bin\Test\analysis\synthetic\TRBSVULambdaDecisionComparison.class").Hash)")-join"`n"
            [IO.File]::WriteAllText($config,$configText,[Text.UTF8Encoding]::new($false))
            Job "compare_${rep}_$qn" 'comparison' 0 'comparison' 'RCSAA_Chi2' $r $caseInput '' "$Root\comparison\results\$rep\$qn" @()
            $jobs[$jobs.Count-1].config=$config;$jobs[$jobs.Count-1].weights=$weightFile
            RecordHash $caseInput;RecordHash $weightFile;RecordHash $config
        }
    }
    foreach($dist in @('normal','lognormal')){foreach($cv in @('cv010030','cv040060')){foreach($r in 1..5){
        $rep='rep_{0:D3}'-f$r;$cell="${dist}_$cv"
        $caseInput=if($dist-eq'normal'){"$NormalInputRoot\$cv\input\$rep"}else{"$LognormalInputRoot\lognormal_$cv\input\$rep"}
        foreach($method in @('D','SAA-All','RF-CSAA')){
            Job "${cell}_${rep}_$method" 'distribution' 0 'base' $method $r $caseInput '' "$Root\distribution\$cell\experiment1\$rep\$method" @()
        }
        $choice="$Root\distribution\$cell\experiment1\$rep\RF-CSAA\queries\query_000\validation\context_candidate.csv"
        Job "${cell}_${rep}_C-Chi2" 'distribution' 1 'robust' 'C-Chi2' $r $caseInput $choice "$Root\distribution\$cell\experiment2\$rep\C-Chi2" @("${cell}_${rep}_RF-CSAA")
        $manifest=Import-Csv -LiteralPath "$caseInput\queries\queries.tsv" -Delimiter "`t"
        if($manifest.Count-ne40-or@($manifest|Where-Object{$_.query_type-ne'RANDOM'}).Count){throw 'Wrong distribution query pool'}
        foreach($q in $manifest){RecordHash (Join-Path "$caseInput\queries" $q.instance_file)}
    }}}
}
$hashes|Export-Csv -LiteralPath "$control\input_hashes.csv" -NoTypeInformation -Encoding UTF8
$plan=[pscustomobject]@{mode=$Mode;created=(Get-Date -Format o);deployment=$deployment;java=$java;python=$python;cplexJar="$cplex\lib\cplex.jar";native=$native;license=$license;parallel=4;solverThreads=4;jobs=$jobs}
$plan|ConvertTo-Json -Depth 9|Set-Content -LiteralPath "$control\manifest.json" -Encoding UTF8
[pscustomobject]@{state='PREPARED';jobs=$jobs.Count;mode=$Mode;parallel=4;solverThreads=4;updated=(Get-Date -Format o)}|ConvertTo-Json|Set-Content -LiteralPath "$control\status.json" -Encoding UTF8
Write-Output "PREPARED mode=$Mode jobs=$($jobs.Count) root=$Root"
