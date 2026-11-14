#!/usr/bin/env python3
"""
Summarise JUnit XML results (Gradle's TEST-*.xml) in the CI log and the job summary.

    python3 scripts/ci/junit_summary.py --title "Integration · dev" app/build/test-results/testDebugUnitTest

Prints passed / failed / skipped counts and, for each failure, the test name and the first lines of
its message; writes the same as Markdown to $GITHUB_STEP_SUMMARY and emits one `::error` (or, with
--warn, `::warning`) annotation per failure. Always exits 0: Gradle already decides whether the job
fails. Standard library only.
"""
import argparse
import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

MESSAGE_LINES = 6
ANNOTATION_LIMIT = 20


def escape_data(text):
    return text.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')


def escape_property(text):
    return escape_data(text).replace(':', '%3A').replace(',', '%2C')


def collect(paths):
    totals = {'tests': 0, 'failed': 0, 'skipped': 0}
    failures = []
    files = []
    for path in paths:
        path = Path(path)
        if path.is_dir():
            files += sorted(path.rglob('TEST-*.xml'))
        elif path.is_file():
            files.append(path)
    for file in files:
        try:
            root = ET.parse(file).getroot()
        except (ET.ParseError, OSError) as error:
            print(f'Could not read {file}: {error}')
            continue
        for case in root.iter('testcase'):
            totals['tests'] += 1
            if case.find('skipped') is not None:
                totals['skipped'] += 1
                continue
            problem = case.find('failure')
            if problem is None:
                problem = case.find('error')
            if problem is None:
                continue
            totals['failed'] += 1
            text = (problem.get('message') or problem.text or problem.get('type') or 'failed').strip()
            lines = [line for line in text.splitlines() if line.strip()][:MESSAGE_LINES]
            failures.append({
                'name': f'{case.get("classname", "?").rsplit(".", 1)[-1]}.{case.get("name", "?")}',
                'message': '\n'.join(lines),
            })
    return files, totals, failures


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('paths', nargs='*', default=['app/build/test-results'])
    parser.add_argument('--title', default='Tests')
    parser.add_argument('--warn', action='store_true', help='annotate failures as warnings (non-gating jobs)')
    args = parser.parse_args()

    files, totals, failures = collect(args.paths)
    if not files:
        line = f'{args.title}: no test results (the build failed before the tests ran?)'
        print(line)
        lines = [f'### {args.title}', '', line]
    else:
        passed = totals['tests'] - totals['failed'] - totals['skipped']
        counts = f'{passed} passed, {totals["failed"]} failed, {totals["skipped"]} skipped'
        if totals['tests'] and totals['skipped'] == totals['tests']:
            counts += ' (all skipped: is the server/key configured?)'
        print(f'{args.title}: {counts}')
        for failure in failures:
            print(f'  FAILED {failure["name"]}')
            for message_line in failure['message'].splitlines():
                print(f'         {message_line}')
        lines = [f'### {args.title}: {"failed" if failures else "passed"}', '', counts]
        if failures:
            lines += ['', '| Test | Message |', '|---|---|']
            for failure in failures:
                message = failure['message'].replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
                message = message.replace('|', '\\|').replace('\n', '<br>')
                lines.append(f'| `{failure["name"]}` | {message} |')

        command = 'warning' if args.warn else 'error'
        for failure in failures[:ANNOTATION_LIMIT]:
            title = escape_property(f'{args.title}: {failure["name"]}')
            print(f'::{command} title={title}::{escape_data(failure["message"])}')

    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a', encoding='utf-8') as out:
            out.write('\n'.join(lines) + '\n\n')


if __name__ == '__main__':
    main()
    sys.exit(0)
