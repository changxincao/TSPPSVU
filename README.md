# TSPP-SVU

Research code for transportation service procurement under contextual stochastic uncertainty. The repository contains the Java optimization models, synthetic-data experiment runners, and the Python helpers used by the TRB revision experiments.

## Repository layout

- `src/Basic`: data structures and procurement parameters.
- `src/Model`: deterministic, SAA/CSAA, RCSAA, divergence-DRO, Wasserstein-DRO, and decomposition models.
- `src/Helper`: data loading, weighting, scaling, evaluation, logging, and experiment utilities.
- `src/Test/analysis/synthetic`: synthetic-data generators, validation code, and experiment runners.
- `analysis/trb_svu`: Python helpers for random-forest weights and partial cross-moment models.
- `scripts`: local batch and post-processing scripts. Some historical scripts retain machine-specific paths and must be adapted before use on another computer.

Generated results, local datasets, solver logs, virtual environments, and revision records are intentionally not versioned.

## Requirements

- Java 21 or newer (the current environment uses Java 22).
- IBM ILOG CPLEX Optimization Studio 22.1.1, including `cplex.jar` and the native CPLEX libraries.
- MOSEK 11, including `mosek.jar`, a licensed MOSEK installation, and its Python package for the PCM experiments.
- Python with NumPy, scikit-learn, RSOME, and MOSEK for the Python-assisted experiments.

CPLEX and MOSEK are proprietary dependencies and are not included. The checked-in Eclipse `.classpath` and several historical scripts contain the original Windows installation paths; update them for the local installation before building or running.

## Compile

From PowerShell, replace the two library paths as needed:

```powershell
$sources = Get-ChildItem src -Recurse -Filter '*.java' | ForEach-Object FullName
javac -encoding UTF-8 `
  -cp 'C:\path\to\cplex.jar;C:\path\to\mosek.jar' `
  -d bin $sources
```

The full source tree was verified with standard `javac` on 2026-09-16.

## Main revision workflow

The current Experiment 1/2 batch entry point is:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run_trb_svu_experiment12_batch.ps1
```

Before using it on another machine, configure the solver library paths and create the local `.venv-rsome` Python environment. The runner generates frozen instances, validation checkpoints, solver logs, final decisions, and OOS evaluation outputs under its configured output directory.

## Reproducibility notes

- Formal runners record seeds, instance hashes, source fingerprints, solver status, bounds/gaps when available, selected parameters, decisions, and OOS results.
- CPLEX/MOSEK license files and Olist-derived local data are not distributed in this repository.
- No open-source license has been assigned yet; all rights remain with the repository owner unless a license file is added later.
