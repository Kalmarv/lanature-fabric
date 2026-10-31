"""Lenient reader for Bedrock pack JSON: // and /* */ comments, trailing commas, BOM."""
import json
import re


def strip_comments(text):
    out = []
    i, n, in_str = 0, len(text), False
    while i < n:
        c = text[i]
        if in_str:
            out.append(c)
            if c == '\\' and i + 1 < n:
                out.append(text[i + 1])
                i += 2
                continue
            if c == '"':
                in_str = False
            i += 1
            continue
        if c == '"':
            in_str = True
        elif text.startswith('//', i):
            j = text.find('\n', i)
            i = n if j < 0 else j
            continue
        elif text.startswith('/*', i):
            j = text.find('*/', i)
            i = n if j < 0 else j + 2
            continue
        out.append(c)
        i += 1
    return ''.join(out)


_TRAILING = re.compile(r',(\s*[}\]])')


def loads(text):
    text = text.lstrip('﻿')
    return json.loads(_TRAILING.sub(r'\1', strip_comments(text)), strict=False)


def load(path):
    with open(path, encoding='utf-8-sig') as f:
        return loads(f.read())
