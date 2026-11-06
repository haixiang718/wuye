"""Prune reviewed retired-table blocks from the two Navicat seed files.

This works on bytes and does not execute SQL. Default is plan-only. Retained table
blocks, preamble and footer are copied verbatim. Unexpected SQL fails closed.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
ALLOWLIST = ROOT / 'scripts/v2-retired-tables.json'
SEED_ROOT = ROOT / 'wuye-b/MicroCommunity-v0.8/docs/db'
FILES = {'TT': SEED_ROOT / 'tt0.6_商业版.sql',
         'hc_community': SEED_ROOT / 'hc_community0.6_商业版.sql'}
IDENTIFIER = re.compile(r'^[A-Za-z_][A-Za-z0-9_]*$')
SPECIAL = re.compile(
    rb"'(?:\\[\s\S]|''|[^'\\])*'|\"(?:\\[\s\S]|\"\"|[^\"\\])*\"|"
    rb'`(?:``|[^`])*`|/\*[\s\S]*?\*/|--(?=[\t \r\n]|$)[^\r\n]*|\#[^\r\n]*|;')
HEADER = re.compile(rb'-- Table structure for ([A-Za-z_][A-Za-z0-9_]*)$')
RECORDS = re.compile(rb'-- Records of ([A-Za-z_][A-Za-z0-9_]*)$')
DIVIDER = re.compile(rb'-- -+$')


def _events(data):
    """Find statements and top-level comments, skipping quoted literal content."""
    events, cursor, start = [], 0, None
    for match in SPECIAL.finditer(data):
        gap = data[cursor:match.start()]
        if any(mark in gap for mark in (b"'", b'"', b'`', b'/*')):
            raise ValueError('Unterminated quoted token or comment')
        if gap.strip() and start is None:
            start = cursor + len(gap) - len(gap.lstrip())
        token = match.group()
        if token.startswith((b'--', b'#', b'/*')):
            if token.startswith((b'/*!', b'/*+')):
                raise ValueError('Executable comments are outside the supported seed format')
            if start is None:
                events.append(('comment', match.start(), match.end(), token))
        elif token == b';':
            if start is None:
                raise ValueError('Unexpected empty SQL statement')
            events.append(('statement', start, match.end(), data[start:match.end()]))
            start = None
        elif start is None:
            start = match.start()
        cursor = match.end()
    if start is not None or data[cursor:].strip():
        raise ValueError('Incomplete SQL statement or unrecognized trailing content')
    return events


def _header_bounds(events, index, marker):
    if index == 0 or index + 1 >= len(events):
        raise ValueError('Incomplete table header')
    left, right = events[index - 1], events[index + 1]
    if left[0] != 'comment' or right[0] != 'comment' or not DIVIDER.fullmatch(left[3]) or not DIVIDER.fullmatch(right[3]):
        raise ValueError('Malformed table header delimiters')
    return left[1], right[2]


def _parse(data):
    events = _events(data)
    headers = []
    for index, event in enumerate(events):
        if event[0] != 'comment':
            continue
        match = HEADER.fullmatch(event[3])
        if match:
            start, end = _header_bounds(events, index, HEADER)
            headers.append((match[1].decode('ascii'), index, start, end))
        elif event[3].startswith(b'-- Table structure for '):
            raise ValueError('Unsupported table identifier')
    if not headers:
        raise ValueError('No Navicat table sections found')
    if len({item[0] for item in headers}) != len(headers):
        raise ValueError('Duplicate table section')
    prefix = data[:headers[0][2]]
    for kind, start, end, body in events:
        if start >= headers[0][2]:
            break
        if kind == 'statement' and not re.fullmatch(rb'SET\s+FOREIGN_KEY_CHECKS\s*=\s*0\s*;', body, re.I):
            raise ValueError('Unexpected statement before table sections')

    chunks, footer = {}, b''
    for number, (name, index, start, header_end) in enumerate(headers):
        limit = headers[number + 1][2] if number + 1 < len(headers) else len(data)
        selected = [event for event in events[index + 1:] if event[1] < limit]
        statements = [event for event in selected if event[0] == 'statement']
        quoted = re.escape(name.encode('ascii'))
        if len(statements) < 2:
            raise ValueError('Table section lacks DROP and CREATE')
        if not re.fullmatch(rb'DROP\s+TABLE\s+IF\s+EXISTS\s+`' + quoted + rb'`\s*;', statements[0][3], re.I):
            raise ValueError('DROP target differs from table header')
        if not re.match(rb'CREATE\s+TABLE\s+`' + quoted + rb'`\s*\(', statements[1][3], re.I):
            raise ValueError('CREATE target differs from table header')
        record_markers = [(i, event) for i, event in enumerate(events)
                          if start < event[1] < limit and event[0] == 'comment' and RECORDS.fullmatch(event[3])]
        if len(record_markers) != 1 or RECORDS.fullmatch(record_markers[0][1][3])[1] != name.encode('ascii'):
            raise ValueError('Missing or mismatched records header')
        record_index, record = record_markers[0]
        _, record_end = _header_bounds(events, record_index, RECORDS)
        if record[1] < statements[1][2]:
            raise ValueError('Records header occurs before CREATE completion')
        last_content = record_end
        footer_started = False
        for event in statements[2:]:
            if not footer_started and re.match(rb'INSERT\s+INTO\s+`' + quoted + rb'`\s+(?:VALUES\b|\()', event[3], re.I):
                if event[1] < record_end:
                    raise ValueError('Row appears before records header')
                last_content = event[2]
                continue
            if number == len(headers) - 1 and re.fullmatch(rb'SET\s+FOREIGN_KEY_CHECKS\s*=\s*1\s*;', event[3], re.I):
                footer_started = True
                continue
            raise ValueError('Unexpected or cross-table statement in table section')
        if number == len(headers) - 1:
            while last_content < len(data) and data[last_content:last_content + 1].isspace():
                last_content += 1
            chunks[name] = data[start:last_content]
            footer = data[last_content:]
        else:
            chunks[name] = data[start:limit]
    return prefix, chunks, footer


def prune_dump(data, retired):
    if not isinstance(data, bytes):
        raise TypeError('Seed content must be bytes')
    retired = set(retired)
    if any(not isinstance(name, str) or not IDENTIFIER.fullmatch(name) for name in retired):
        raise ValueError('Retirement list requires exact SQL identifiers')
    prefix, chunks, footer = _parse(data)
    removed = sorted(set(chunks) & retired)
    retained = {name: chunk for name, chunk in chunks.items() if name not in retired}
    output = prefix + b''.join(retained.values()) + footer
    if not retained:
        raise ValueError('Refuse to remove every table from a seed')
    after_prefix, after_chunks, after_footer = _parse(output)
    if prefix != after_prefix or retained != after_chunks or footer != after_footer:
        raise ValueError('Retained seed content changed')
    return output, {
        'beforeTableCount': len(chunks), 'afterTableCount': len(retained),
        'removedTables': removed, 'retainedTables': list(retained),
        'beforeBytes': len(data), 'afterBytes': len(output),
        'beforeSha256': hashlib.sha256(data).hexdigest(),
        'afterSha256': hashlib.sha256(output).hexdigest(),
        'retainedChunksUnchanged': True, 'preambleAndFooterUnchanged': True,
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args(argv)
    policy = json.loads(ALLOWLIST.read_text(encoding='utf-8'))
    if policy.get('version') != 1 or set(policy.get('schemas', {})) != set(FILES):
        raise ValueError('Unexpected reviewed policy')
    planned = []
    # Validate both files before changing either file.
    for schema, path in FILES.items():
        original = path.read_bytes()
        output, report = prune_dump(original, policy['schemas'][schema])
        planned.append((path, original, output, {'schema': schema, **report}))
    for path, original, output, report in planned:
        if args.apply and output != original:
            if path.read_bytes() != original:
                raise ValueError('Seed changed during planning')
            temporary = path.with_suffix(path.suffix + '.tmp')
            temporary.write_bytes(output)
            temporary.replace(path)
        print(json.dumps({'applied': args.apply, **report}, ensure_ascii=True))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
