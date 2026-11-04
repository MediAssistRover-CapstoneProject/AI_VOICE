"""Read-only migration comparison; never changes database history or migrations."""
import argparse
import hashlib
from pathlib import Path


def compare(local, official):
    def read(root):
        result = {}
        for p in Path(root).glob('V*__*.sql'):
            version = p.name.split('__')[0]
            if version in result:
                raise ValueError('Duplicate migration: ' + version)
            result[version] = (p.name, hashlib.sha256(p.read_bytes()).hexdigest())
        if not result:
            raise ValueError('No migrations found: ' + str(root))
        return result
    ours, theirs = read(local), read(official)
    conflicts = [v for v in ours.keys() & theirs.keys() if ours[v] != theirs[v]]
    for v in sorted(conflicts):
        print('CONFLICT', ours[v][0], '<->', theirs[v][0])
    print('Local-only:', ', '.join(sorted(ours.keys() - theirs.keys())))
    print('Official-only:', ', '.join(sorted(theirs.keys() - ours.keys())))
    return conflicts


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('official', help='Official backend migration directory')
    p.add_argument('--local', default='backend/src/main/resources/db/migration')
    args = p.parse_args()
    raise SystemExit(1 if compare(args.local, args.official) else 0)
