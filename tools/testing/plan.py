#!/usr/bin/env python3
"""Select conservative test batches from a Git diff; missing history means full coverage."""
import argparse
import fnmatch
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
GROUPS = json.loads((ROOT / 'tools/testing/groups.json').read_text())
JAVA = set(GROUPS['java'])
RUST = set(GROUPS['rust']) | {'serial'}


def select(paths, full=False):
    java, rust = set(), set()
    if full:
        return sorted(JAVA), sorted(RUST)
    for path in paths:
        path = path.replace('\\', '/')
        if (path.startswith(('tools/testing/', '.github/workflows/'))
                or path in ('build.gradle', 'common/build.gradle', 'settings.gradle', 'gradle.properties')
                or path.startswith('gradle/')):
            return sorted(JAVA), sorted(RUST)
        if path.startswith('rust_engine/'):
            part = path.removeprefix('rust_engine/src/').split('/')[0].removesuffix('.rs')
            affected = {
                'animation': {'animation', 'model'}, 'vmd_smoothing': {'animation', 'model'},
                'physics': {'physics', 'model', 'serial'},
                'model': {'model', 'animation', 'physics', 'vr', 'serial'},
                'skeleton': {'animation', 'model', 'physics', 'vr'},
                'morph': {'animation', 'model'}, 'skinning': {'animation', 'model'},
                'texture': {'core', 'model'}, 'jni_log': {'core'},
                'vr': {'vr', 'model'}, 'vrm_runtime': {'vr', 'model'},
                'jni_bridge': {'model', 'serial'},
                'evaluation_profile': {'model'}, 'evaluation_parallelism': {'model'},
            }.get(part, RUST)
            rust.update(affected)
            # Java fakes cannot verify changed JNI contracts, but exercise their consumers.
            if part in ('jni_bridge', 'lib', 'build'):
                java.update(JAVA)
            continue
        if path.startswith(('fabric/', 'neoforge/')):
            java.update(JAVA)
            continue
        if not path.startswith('common/'):
            # Known documentation/assets are test-free; unknown build/tool files are conservative.
            if (path.startswith(('doc/', 'plans/')) or path.endswith(('.md', '.png', '.jpg', '.pdf', '.docx'))
                    or path in ('LICENSE', 'NOTICE', '.gitignore') or path.startswith('LICENSE-')):
                continue
            java.update(JAVA)
            rust.update(RUST)
            continue
        relative = path.split('com/shiroha/mmdskin/', 1)[-1]
        if relative.startswith(('stage/', 'ui/stage/')):
            java.add('stage')
        elif relative.startswith(('render/', 'texture/', 'ui/paperdoll/')):
            java.update(('render', 'compat'))
        elif relative.startswith('compat/'):
            java.update(('compat', 'render', 'model'))
        elif relative.startswith(('ui/selector/', 'ui/config/')):
            java.update(('config', 'model', 'stage'))
        elif relative.startswith(('ui/about/', 'debug/')):
            java.add('config')
        elif path.startswith('common/src/main/resources/assets/mmdskin/lang/'):
            java.add('config')
        else:
            java.update(JAVA)
    return sorted(java), sorted(rust)


def check_inventory():
    """Every Java test has exactly one full-regression owner, including future tests."""
    names = []
    populated = set()
    for file in (ROOT / 'common/src/test/java').rglob('*Test.java'):
        name = file.relative_to(ROOT / 'common/src/test/java').as_posix().replace('.java', '.class')
        names.append(name)
        owners = [group for group, patterns in GROUPS['java'].items()
                  if any(fnmatch.fnmatchcase(name, pattern) for pattern in patterns)]
        if len(owners) != 1:
            raise ValueError(f'{file}: expected one test group, found {owners}')
        populated.add(owners[0])
    if populated != JAVA:
        raise ValueError(f'Empty Java batches: {sorted(JAVA - populated)}')
    for pattern in GROUPS['quick']:
        if not any(fnmatch.fnmatchcase(name, pattern) for name in names):
            raise ValueError(f'Quick test no longer exists: {pattern}')


def changed_paths(base, head):
    if not base or not base.strip('0'):
        raise ValueError('No usable base commit; selecting full regression')
    result = subprocess.run(['git', 'diff', '--name-only', '--no-renames', '-z', base, head],
                            cwd=ROOT, check=True, capture_output=True)
    return [p for p in result.stdout.decode().split('\0') if p]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base', default='HEAD~1')
    parser.add_argument('--head', default='HEAD')
    parser.add_argument('--full', action='store_true')
    parser.add_argument('--github-output', type=Path)
    args = parser.parse_args()
    check_inventory()
    full, reason = args.full, 'explicit full regression' if args.full else 'changed paths'
    paths = []
    if not full:
        try:
            paths = changed_paths(args.base, args.head)
        except (ValueError, subprocess.CalledProcessError):
            full, reason = True, 'missing history: full regression'
    java, rust = select(paths, full)
    result = {
        'java': {'include': [{'task': 'test' + name.capitalize()} for name in java]},
        'rust': ','.join(rust), 'has_java': str(bool(java)).lower(),
        'has_rust': str(bool(rust)).lower(), 'reason': reason,
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if args.github_output:
        with args.github_output.open('a') as stream:
            for key, value in result.items():
                encoded = json.dumps(value, separators=(',', ':')) if isinstance(value, dict) else value
                stream.write(f'{key}={encoded}\n')


if __name__ == '__main__':
    main()
