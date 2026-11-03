"""Build/verify a private, non-payment Linux preparation archive; never deploy it."""
import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import tarfile
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SERVICES = ('eureka', 'order', 'api', 'front', 'job', 'business')
SECRET_KEY = re.compile(r'password|secret|token|private.?key|access.?key|(?:^|[._-])key\s*[:=]', re.I)
ASSIGNMENT = re.compile(r'^(\s*[^#;\s][^:=]*?\s*[:=]\s*)(.*)$')
PLACEHOLDER = 'REPLACE_IN_PRODUCTION'
CONFIG_EXTENSIONS = {'.yml', '.yaml', '.properties'}
PRIVATE_EXTENSIONS = {'.pem', '.key', '.p12', '.pfx', '.jks', '.sql', '.xlsx', '.xls', '.docx'}
EXCLUDED_PARTS = {'node_modules', 'test', 'tests', '.git', '.runtime', '.tools', '__pycache__', 'logs', 'uploads'}


def digest(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def sanitize_config(text, name):
    """Remove historical credentials and non-loopback literal hosts from project defaults."""
    lines = []
    for line in text.splitlines():
        if line.lstrip().startswith(('#', ';')):
            continue
        match = ASSIGNMENT.match(line)
        if match and SECRET_KEY.search(match[1]):
            value = match[2].strip()
            if value.startswith(('|', '>')) or value.endswith('\\'):
                raise ValueError('Sensitive multiline config requires manual review: ' + name)
            line = match[1] + (PLACEHOLDER if value and value not in ("''", '""') else value)
        # Historical source defaults point at machines unrelated to production.
        line = re.sub(r'(?<![\d.])(?:\d{1,3}\.){3}\d{1,3}(?![\d.])',
                      lambda m: m[0] if m[0].startswith('127.') or m[0] == '0.0.0.0' else 'PRODUCTION_HOST_REQUIRED', line)
        line = re.sub(r'((?:jdbc:mysql|https?|ftp)://)([a-zA-Z0-9.-]+)(?=[:/\s]|$)',
                      lambda m: m[0] if (m[2] in ('localhost', 'api.weixin.qq.com', 'api.mch.weixin.qq.com')
                                        or m[2].startswith('127.')) else m[1] + 'PRODUCTION_HOST_REQUIRED', line)
        lines.append(line)
    return '\n'.join(lines) + '\n'


def rewrite_jar(source, destination):
    """Rewrite project config recursively; preserve stored nested JARs required by Boot."""
    def rewritten(raw, nested=False):
        output = io.BytesIO()
        with zipfile.ZipFile(io.BytesIO(raw)) as incoming, zipfile.ZipFile(output, 'w') as outgoing:
            if incoming.testzip() is not None:
                raise ValueError('Invalid source JAR CRC')
            names = incoming.namelist()
            if len(names) != len(set(names)):
                raise ValueError('Duplicate JAR entries')
            if any(re.fullmatch(r'META-INF/[^/]+\.(SF|RSA|DSA|EC)', name, flags=re.I) for name in names):
                raise ValueError('Signed project JAR requires a separately rebuilt release artifact')
            for info in incoming.infolist():
                safe_name(info.filename.rstrip('/'))
                if Path(info.filename).suffix.lower() in PRIVATE_EXTENSIONS:
                    raise ValueError('Private material inside project JAR: ' + info.filename)
                data = incoming.read(info)
                own_resource = nested or info.filename.startswith('BOOT-INF/classes/')
                if own_resource and Path(info.filename).suffix.lower() in CONFIG_EXTENSIONS:
                    try:
                        encoding = 'iso-8859-1' if Path(info.filename).suffix.lower() == '.properties' else 'utf-8'
                        data = sanitize_config(data.decode(encoding), info.filename).encode(encoding)
                    except UnicodeError as error:
                        raise ValueError('Unreadable project config: ' + info.filename) from error
                elif info.filename.startswith('BOOT-INF/lib/') and re.match(r'(java110-|service-)', Path(info.filename).name):
                    data = rewritten(data, nested=True)
                elif own_resource and Path(info.filename).suffix.lower() in ('.xml', '.json'):
                    text = data.decode('utf-8', errors='replace')
                    literal = (r'<property\b[^>]*name=["\'](?:password|appSecret|secret|token)["\'][^>]*value=["\'][^"\']+'
                               r'|["\'](?:appSecret|password|secret|token)["\']\s*:\s*["\'][^"\']+')
                    if re.search(literal, text, flags=re.I):
                        raise ValueError('Sensitive XML/JSON config requires manual review: ' + info.filename)
                outgoing.writestr(info, data)
        return output.getvalue()
    destination.write_bytes(rewritten(source.read_bytes()))


def safe_name(name):
    path = PurePosixPath(name)
    if (not name or path.is_absolute() or '\\' in name or ':' in name
            or '..' in path.parts or str(path) != name):
        raise ValueError('Unsafe archive path: ' + name)
    return name


def accepted(path):
    return not (any(part in EXCLUDED_PARTS or part.startswith('.') for part in path.parts)
                or path.suffix.lower() in PRIVATE_EXTENSIONS or path.name.endswith(('.map', '.log', '.bak')))


def copy_tree(source, target):
    if source.is_symlink():
        raise ValueError('Input symlink is not allowed')
    for file in sorted(source.rglob('*')):
        relative = file.relative_to(source)
        if not accepted(relative):
            continue
        if file.is_symlink():
            raise ValueError('Input symlink is not allowed: ' + str(relative))
        if file.is_file():
            destination = target / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(file, destination)


def build(root, output):
    root, output = Path(root).resolve(), Path(output).absolute()
    if output.exists():
        raise FileExistsError('Release output already exists')
    jars = {name: root / f'wuye-b/MicroCommunity-v0.8/service-{name}/target/service-{name}.jar' for name in SERVICES}
    web = root / 'wuye-f/MicroCommunityWeb-v0.8'
    h5 = root / 'wuye-xcx2/WechatOwnerService-v0.8/unpackage/dist/build/h5'
    required = [*jars.values(), web / 'app.js', web / 'package.json', web / 'package-lock.json',
                h5 / 'index.html', root / 'scripts/wechat-test-gateway.cjs', root / 'deploy/linux/README.md']
    data_sources = {'business': 'user', 'business-community': 'community', 'business-report': 'report',
                    'order': 'order', 'job': 'job'}
    required.extend(root / f'wuye-b/MicroCommunity-v0.8/service-{service}/src/main/resources/dataSource.yml'
                    for service in data_sources.values())
    for path in required:
        if not path.is_file():
            raise ValueError('Missing required artifact: ' + str(path.relative_to(root)))
        if path.is_symlink():
            raise ValueError('Input symlink is not allowed')
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='release-', dir=output.parent) as temporary:
        stage = Path(temporary) / 'content'
        (stage / 'java').mkdir(parents=True)
        for name, jar in jars.items():
            rewrite_jar(jar, stage / f'java/service-{name}.jar')
        for name in ('app.js', 'package.json', 'package-lock.json'):
            (stage / 'admin').mkdir(exist_ok=True)
            shutil.copyfile(web / name, stage / 'admin' / name)
        for name in ('routes', 'views', 'public'):
            if (web / name).exists():
                copy_tree(web / name, stage / 'admin' / name)
        copy_tree(h5, stage / 'h5')
        copy_tree(root / 'deploy/linux', stage / 'linux')
        examples = stage / 'linux/config/examples'
        examples.mkdir(parents=True, exist_ok=True)
        for name, service in data_sources.items():
            source = root / f'wuye-b/MicroCommunity-v0.8/service-{service}/src/main/resources/dataSource.yml'
            text = sanitize_config(source.read_text(encoding='utf-8'), source.name)
            text = re.sub(r'(?m)^(\s*username:\s*).*$', r'\1REPLACE_IN_PRODUCTION', text)
            text = re.sub(r'(?m)^(\s*(?:minIdle|initialSize):\s*).*$', r'\g<1>0', text)
            text = re.sub(r'(?m)^(\s*(?:poolPreparedStatements|sql.show):\s*).*$', r'\1false', text)
            (examples / (name + '-datasource.yml.example')).write_text(text, encoding='utf-8')
        (stage / 'gateway').mkdir()
        shutil.copyfile(root / 'scripts/wechat-test-gateway.cjs', stage / 'gateway/wechat-test-gateway.cjs')
        (stage / 'bin').mkdir()
        shutil.copyfile(Path(__file__), stage / 'bin/release.py')
        manifest = {'format': 1, 'services': list(SERVICES), 'paymentEnabled': False,
                    'scheduledTasksEnabled': False, 'linuxRuntimeValidated': False,
                    'purpose': 'Private non-payment deployment preparation; external configuration and Linux acceptance required',
                    'sourceJars': {name: digest(path) for name, path in jars.items()},
                    'jarTransform': 'Historical credentials and literal remote hosts redacted in project config resources; originals unchanged',
                    'files': {}}
        result = subprocess.run(['git', 'rev-parse', 'HEAD'], cwd=root, capture_output=True, text=True)
        manifest['sourceCommit'] = result.stdout.strip() if result.returncode == 0 else 'unversioned-fixture'
        result = subprocess.run(['git', 'status', '--porcelain'], cwd=root, capture_output=True, text=True)
        manifest['sourceWorktreeDirty'] = bool(result.stdout.strip()) if result.returncode == 0 else True
        for file in sorted(stage.rglob('*')):
            if file.is_file():
                name = file.relative_to(stage).as_posix()
                manifest['files'][name] = {'sha256': digest(file), 'size': file.stat().st_size}
        (stage / 'release-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        pending = Path(temporary) / 'release.tar.gz'
        with tarfile.open(pending, 'w:gz', compresslevel=1) as archive:
            for file in sorted(stage.rglob('*')):
                if file.is_file():
                    info = archive.gettarinfo(str(file), file.relative_to(stage).as_posix())
                    info.uid = info.gid = 0
                    info.uname = info.gname = ''
                    info.mode = 0o750 if file.suffix in ('.sh', '.py') else 0o640
                    with file.open('rb') as stream:
                        archive.addfile(info, stream)
        verify(pending)
        # Hard link publishes atomically without overwriting an existing archive.
        import os
        os.link(pending, output)
    return {'archive': str(output), 'sha256': digest(output), 'files': len(manifest['files']),
            'linuxRuntimeValidated': False, 'paymentEnabled': False}


def verify(archive):
    with tarfile.open(archive, 'r:gz') as incoming:
        members = incoming.getmembers()
        seen = set()
        for member in members:
            safe_name(member.name)
            if not member.isfile() or member.name in seen:
                raise ValueError('Non-regular or duplicate archive member')
            seen.add(member.name)
        if 'release-manifest.json' not in seen:
            raise ValueError('Missing release manifest')
        manifest_member = incoming.getmember('release-manifest.json')
        if manifest_member.size > 8 * 1024 * 1024:
            raise ValueError('Oversized release manifest')
        manifest = json.load(incoming.extractfile(manifest_member))
        expected = manifest.get('files')
        if not isinstance(expected, dict) or set(expected) != seen - {'release-manifest.json'}:
            raise ValueError('Unlisted or missing release files')
        for member in members:
            if member.name == 'release-manifest.json':
                continue
            entry = expected[member.name]
            if member.size != entry.get('size'):
                raise ValueError('Release size mismatch: ' + member.name)
            checksum = hashlib.sha256()
            with incoming.extractfile(member) as stream:
                for block in iter(lambda: stream.read(1024 * 1024), b''):
                    checksum.update(block)
            if checksum.hexdigest() != entry.get('sha256'):
                raise ValueError('Release checksum mismatch: ' + member.name)
        return {'files': len(expected), 'sha256': digest(archive), 'integrity': 'passed',
                'linuxRuntimeValidated': manifest.get('linuxRuntimeValidated', False)}


def verify_tree(directory):
    directory = Path(directory).resolve()
    manifest = json.loads((directory / 'release-manifest.json').read_text(encoding='utf-8'))
    expected = manifest['files']
    found = set()
    for file in directory.rglob('*'):
        if file.is_symlink():
            raise ValueError('Release symlink is not allowed')
        if not file.is_file():
            continue
        name = safe_name(file.relative_to(directory).as_posix())
        if name == 'release-manifest.json':
            continue
        if name not in expected:
            raise ValueError('Unlisted release file: ' + name)
        entry = expected[name]
        if file.stat().st_size != entry['size'] or digest(file) != entry['sha256']:
            raise ValueError('Release checksum mismatch: ' + name)
        found.add(name)
    if found != set(expected):
        raise ValueError('Missing release file')
    return {'files': len(found), 'integrity': 'passed', 'linuxRuntimeValidated': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['build', 'verify', 'verify-tree'])
    parser.add_argument('--archive', type=Path)
    parser.add_argument('--directory', type=Path)
    args = parser.parse_args()
    if args.action == 'verify-tree':
        if not args.directory:
            parser.error('--directory is required for verify-tree')
        result = verify_tree(args.directory)
    elif args.action == 'verify':
        if not args.archive:
            parser.error('--archive is required for verify')
        result = verify(args.archive)
    else:
        from datetime import datetime, timezone
        archive = args.archive or ROOT / '.runtime/releases' / ('wuye-nonpayment-' + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '.tar.gz')
        result = build(ROOT, archive)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, zipfile.BadZipFile, tarfile.TarError) as error:
        raise SystemExit(str(error))
