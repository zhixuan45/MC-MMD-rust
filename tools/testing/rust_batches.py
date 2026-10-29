#!/usr/bin/env python3
"""Compile once, then run selected Rust domains in separate processes."""
import argparse
import json
from pathlib import Path
import subprocess
import sys

from plan import GROUPS, ROOT


def partition(names):
    groups = {key: [] for key in GROUPS['rust']}
    groups['serial'] = []
    for name in names:
        owners = (['serial'] if name in GROUPS['rust_serial'] else
                  [key for key, prefixes in GROUPS['rust'].items()
                   if any(name.startswith(prefix) for prefix in prefixes)])
        if len(owners) != 1:
            raise ValueError(f'{name}: expected one Rust group, found {owners}')
        groups[owners[0]].append(name)
    missing = set(GROUPS['rust_serial']) - set(names)
    if missing:
        raise ValueError(f'Serial test names no longer exist: {sorted(missing)}')
    return groups


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--groups', default='core', help='comma separated domains, or all')
    parser.add_argument('--offline', action='store_true')
    parser.add_argument('--legacy', action='store_true', help='opt-in legacy physics backend')
    parser.add_argument('--list-only', action='store_true')
    args = parser.parse_args()
    requested = list(GROUPS['rust']) + ['serial'] if args.groups == 'all' else args.groups.split(',')
    if not requested or any(g not in set(GROUPS['rust']) | {'serial'} for g in requested):
        parser.error('Unknown or empty Rust batch')
    # Like native platform builds, allow a clean checkout to generate Cargo.lock:
    # this repository intentionally does not track lockfiles.
    command = ['cargo', 'test', '--lib', '--no-run', '--message-format=json',
               '--manifest-path', 'rust_engine/Cargo.toml']
    if args.offline:
        command.append('--offline')
    if args.legacy:
        command.extend(['--features', 'legacy-physics'])
    result = subprocess.run(command, cwd=ROOT, stdout=subprocess.PIPE, text=True, check=True)
    artifacts = [json.loads(line) for line in result.stdout.splitlines() if line.startswith('{')]
    binaries = [Path(a['executable']) for a in artifacts
                if a.get('reason') == 'compiler-artifact' and a.get('executable')
                and a.get('profile', {}).get('test') and a.get('target', {}).get('name') == 'mmd_engine']
    if len(binaries) != 1:
        raise RuntimeError(f'Expected one library test executable, found {len(binaries)}')
    binary = str(binaries[0])
    listing = subprocess.check_output([binary, '--list'], cwd=ROOT, text=True)
    groups = partition([line.removesuffix(': test') for line in listing.splitlines() if line.endswith(': test')])
    for group in dict.fromkeys(requested):
        names = groups[group]
        print(f'Rust batch {group}: {len(names)} tests', flush=True)
        if not names:
            raise ValueError(f'Empty selected Rust batch: {group}')
        if args.list_only:
            continue
        command = [binary, '--exact', *names]
        if group == 'serial':
            command.extend(['--ignored', '--test-threads=1'])
        subprocess.run(command, cwd=ROOT, check=True)


if __name__ == '__main__':
    try:
        main()
    except subprocess.CalledProcessError as error:
        sys.exit(error.returncode)
