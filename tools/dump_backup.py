"""Independent reader for the cross-check in check.sh (Rule 33).

Prints the categories and novels of an LNReader backup in the same format as
`Cli dump`, using only Python's zipfile and json, so the Java reader is compared
with a second implementation rather than with itself.

    python3 -I tools/dump_backup.py BACKUP.zip
"""
import io
import json
import re
import sys
import unicodedata
import zipfile

JS_SPACE = set('\t\n\x0b\x0c\r  ﻿  ')


def js_trim(s):
    """JavaScript String.prototype.trim (SPEC §2.2)."""
    def space(c):
        return c in JS_SPACE or unicodedata.category(c) == 'Zs'
    a, b = 0, len(s)
    while a < b and space(s[a]):
        a += 1
    while b > a and space(s[b - 1]):
        b -= 1
    return s[a:b]


def flag(v):
    if v is True or v == 1:
        return 'true'
    if v is False or v == 0:
        return 'false'
    raise ValueError('not a flag: %r' % (v,))


def main(path):
    out = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', newline='\n')
    with zipfile.ZipFile(path) as z:
        novels = {}
        for name in z.namelist():
            m = re.fullmatch(r'NovelAndChapters/([0-9]+)\.json', name)
            if not m:
                continue
            n = json.loads(z.read(name).decode('utf-8'))
            if n['id'] != int(m.group(1)):
                raise ValueError(name + ': id mismatch')
            novels[n['id']] = n
        categories = json.loads(z.read('Category.json').decode('utf-8'))
    for c in categories:
        ids = sorted({i for i in c['novelIds'] if i in novels})
        out.write('category\t%d\t%s\t[%s]\n' % (c['id'], js_trim(c['name']), ', '.join(str(i) for i in ids)))
    for i in sorted(novels):
        n = novels[i]
        out.write('novel\t%d\t%s\t%s\t%s\t%s\n' % (i, n['pluginId'], flag(n['inLibrary']), flag(n['isLocal']), n['name']))
    out.flush()


if __name__ == '__main__':
    if len(sys.argv) != 2:
        sys.exit('usage: dump_backup.py BACKUP.zip')
    main(sys.argv[1])
