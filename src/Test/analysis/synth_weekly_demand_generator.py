from pathlib import Path
import runpy

if __name__ == '__main__':
    target = Path(__file__).with_name('synthetic').joinpath('synth_weekly_demand_generator.py')
    runpy.run_path(str(target), run_name='__main__')
