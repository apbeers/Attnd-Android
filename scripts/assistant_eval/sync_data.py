#!/usr/bin/env python3
"""Copy the assistant eval's catalogue and DEMO bundle into the unit tests' resources.

The canonical files live in attnd-web: docs/assistant_eval/questions.json and demo_bundle.json (made by
`manage.py export_assistant_eval_bundle`). Run this after either changes:

    python3 scripts/assistant_eval/sync_data.py                      # attnd-web next to this repo (../Attnd-Web)
    python3 scripts/assistant_eval/sync_data.py --web /path/to/Attnd-Web

They land in app/src/test/resources/assistant_eval/ (read by unit/AssistantEval.kt from the test classpath).
"""
import argparse
import json
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--web', default=str(ROOT.parent / 'Attnd-Web'))
    args = parser.parse_args()
    source = Path(args.web) / 'docs' / 'assistant_eval'
    target = ROOT / 'app' / 'src' / 'test' / 'resources' / 'assistant_eval'
    target.mkdir(parents=True, exist_ok=True)
    for name in ('questions.json', 'demo_bundle.json'):
        json.loads((source / name).read_text())  # well formed before it is copied
        shutil.copyfile(source / name, target / name)
    questions = json.loads((target / 'questions.json').read_text())
    print(f'Copied to {target.relative_to(ROOT)}: questions.json v{questions["version"]} '
          f'({len(questions["questions"])} questions), demo_bundle.json.')


if __name__ == '__main__':
    main()
