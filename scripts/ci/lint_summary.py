#!/usr/bin/env python3
"""
Print Android lint's findings in the CI log and annotate them on the run, without failing it.

    python3 scripts/ci/lint_summary.py [app/build/reports/lint-results-debug.xml]

Reads the XML report (`lint { xmlReport = true }` in app/build.gradle), or the SARIF report next to it
when there is no XML. Prints a count per issue id and every finding (file:line, id, message), writes
the same to the job summary ($GITHUB_STEP_SUMMARY) and emits a `::warning` / `::error` annotation
per finding (GitHub shows the first few per step; the log has all of them). Always exits 0: lint
errors already fail the Gradle run; this step only makes the warnings visible. Standard library only.
"""
import json
import os
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from pathlib import Path

ANNOTATION_LIMIT = 50  # per step; GitHub drops the rest anyway


def relative(path):
    if not path:
        return ''
    root = os.environ.get('GITHUB_WORKSPACE') or os.getcwd()
    try:
        return os.path.relpath(path, root) if os.path.isabs(path) else path
    except ValueError:
        return path


def from_xml(report):
    findings = []
    for issue in ET.parse(report).getroot().iter('issue'):
        location = issue.find('location')
        attrs = location.attrib if location is not None else {}
        findings.append({
            'id': issue.get('id', '?'),
            'severity': issue.get('severity', 'Warning'),
            'message': issue.get('message', ''),
            'file': relative(attrs.get('file', '')),
            'line': attrs.get('line', ''),
            'column': attrs.get('column', ''),
        })
    return findings


def from_sarif(report):
    levels = {'error': 'Error', 'warning': 'Warning', 'note': 'Information'}
    findings = []
    for run in json.loads(report.read_text()).get('runs', []):
        for result in run.get('results', []):
            physical = (result.get('locations') or [{}])[0].get('physicalLocation', {})
            region = physical.get('region', {})
            findings.append({
                'id': result.get('ruleId', '?'),
                'severity': levels.get(result.get('level', 'warning'), 'Warning'),
                'message': result.get('message', {}).get('text', ''),
                'file': relative(physical.get('artifactLocation', {}).get('uri', '')),
                'line': str(region.get('startLine', '')),
                'column': str(region.get('startColumn', '')),
            })
    return findings


def escape_data(text):
    return text.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')


def escape_property(text):
    return escape_data(text).replace(':', '%3A').replace(',', '%2C')


def main():
    report = Path(sys.argv[1] if len(sys.argv) > 1 else 'app/build/reports/lint-results-debug.xml')
    sarif = report.with_suffix('.sarif')
    if report.exists():
        findings = from_xml(report)
    elif sarif.exists():
        findings = from_sarif(sarif)
    else:
        print(f'No lint report at {report} (lint did not run?)')
        return
    findings = [f for f in findings if f['severity'] not in ('Ignore',)]
    findings.sort(key=lambda f: (f['severity'] not in ('Fatal', 'Error'), f['id'], f['file'], f['line']))

    if not findings:
        print('Lint: no warnings.')
        return
    counts = Counter((f['severity'], f['id']) for f in findings)
    print(f'Lint: {len(findings)} finding(s)')
    for (severity, issue_id), count in sorted(counts.items()):
        print(f'  {count:3d}  {severity:<11} {issue_id}')
    print()
    for f in findings:
        where = f['file'] + (f':{f["line"]}' if f['line'] else '')
        print(f'{where}: {f["severity"]} [{f["id"]}] {f["message"]}')

    for f in findings[:ANNOTATION_LIMIT]:
        command = 'error' if f['severity'] in ('Fatal', 'Error') else 'warning' if f['severity'] == 'Warning' else 'notice'
        props = [f'title={escape_property("Lint " + f["id"])}']
        if f['file']:
            props.append(f'file={escape_property(f["file"])}')
            if f['line']:
                props.append(f'line={f["line"]}')
            if f['column']:
                props.append(f'col={f["column"]}')
        print(f'::{command} {",".join(props)}::{escape_data(f["message"])}')

    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a', encoding='utf-8') as out:
            out.write(f'### Android lint: {len(findings)} finding(s)\n\n| Severity | Issue | Location | Message |\n|---|---|---|---|\n')
            for f in findings:
                where = f['file'] + (f':{f["line"]}' if f['line'] else '')
                message = f['message'].replace('|', '\\|').replace('\n', ' ')
                out.write(f'| {f["severity"]} | `{f["id"]}` | `{where}` | {message} |\n')


if __name__ == '__main__':
    main()
