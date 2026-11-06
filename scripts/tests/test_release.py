import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location('build_release', Path(__file__).parents[1] / 'build_release.py')
release = importlib.util.module_from_spec(spec)
if spec.loader and spec.origin and Path(spec.origin).exists():
    spec.loader.exec_module(release)


def jar_bytes(entries):
    out = io.BytesIO()
    with zipfile.ZipFile(out, 'w') as z:
        for name, data in entries.items():
            z.writestr(name, data, compress_type=zipfile.ZIP_STORED)
    return out.getvalue()


class ReleaseTests(unittest.TestCase):
    def fixture(self, base):
        root = base / 'repo'
        nested = jar_bytes({'application.yml': 'password: nested-secret\nserver: 192.0.2.1\n'})
        for name in ('eureka', 'order', 'api', 'front', 'job', 'business'):
            p = root / f'wuye-b/MicroCommunity-v0.8/service-{name}/target/service-{name}.jar'
            p.parent.mkdir(parents=True)
            p.write_bytes(jar_bytes({
                'META-INF/MANIFEST.MF': 'Main-Class: org.springframework.boot.loader.JarLauncher\n',
                'BOOT-INF/classes/application.yml': 'spring:\n  password: original-secret\n',
                'BOOT-INF/classes/java110.properties': 'java110.ftpUserPassword = ftp-secret\n',
                'BOOT-INF/lib/java110-core-1.0.jar': nested,
            }))
        web = root / 'wuye-f/MicroCommunityWeb-v0.8'
        for name, content in {'package.json': '{}', 'package-lock.json': '{}', 'app.js': 'module.exports={};',
                              'lib/static-assets.cjs': 'exports.createStaticAssets=()=>{};',
                              'public/index.html': '<html></html>', 'public/.env': 'secret=private',
                              'test/test.js': 'TEST_FIXTURE', 'node_modules/private/key.pem': 'PRIVATE KEY'}.items():
            p = web / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(content)
        h5 = root / 'wuye-xcx2/WechatOwnerService-v0.8/unpackage/dist/build/h5'
        h5.mkdir(parents=True)
        (h5 / 'index.html').write_text('<html>H5</html>')
        (h5 / 'app.js').write_text('window.ready=true;')
        (root / '.runtime').mkdir()
        (root / '.runtime/wechat-domain.json').write_text('{"appSecret":"runtime-secret"}')
        (root / 'scripts').mkdir()
        (root / 'scripts/wechat-test-gateway.cjs').write_text('exports.createGateway=()=>{};')
        (root / 'deploy/linux').mkdir(parents=True)
        (root / 'deploy/linux/README.md').write_text('Review configuration before launch.')
        for name in ('user', 'community', 'report', 'order', 'job'):
            p = root / f'wuye-b/MicroCommunity-v0.8/service-{name}/src/main/resources/dataSource.yml'
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text('password: source-secret\nusername: original\nurl: jdbc:mysql://192.0.2.1:3306/TT\n')
        return root

    def test_build_excludes_runtime_and_dependencies_and_sanitizes_nested_jar_without_changing_source(self):
        with tempfile.TemporaryDirectory() as d:
            base = Path(d)
            root = self.fixture(base)
            source = root / 'wuye-b/MicroCommunity-v0.8/service-front/target/service-front.jar'
            original = source.read_bytes()
            target = base / 'release.tar.gz'
            release.build(root, target)
            result = release.verify(target)
            self.assertGreater(result['files'], 8)
            self.assertEqual(source.read_bytes(), original)
            with tarfile.open(target) as t:
                names = t.getnames()
                self.assertIn('admin/lib/static-assets.cjs', names)
                self.assertFalse(any('.runtime' in n or 'node_modules' in n or '/test/' in n or '.env' in n for n in names))
                data = t.extractfile('java/service-front.jar').read()
                with zipfile.ZipFile(io.BytesIO(data)) as z:
                    self.assertNotIn(b'original-secret', z.read('BOOT-INF/classes/application.yml'))
                    self.assertNotIn(b'ftp-secret', z.read('BOOT-INF/classes/java110.properties'))
                    info = z.getinfo('BOOT-INF/lib/java110-core-1.0.jar')
                    self.assertEqual(info.compress_type, zipfile.ZIP_STORED)
                    self.assertIsNone(z.testzip())
                    with zipfile.ZipFile(io.BytesIO(z.read(info))) as child:
                        self.assertNotIn(b'nested-secret', child.read('application.yml'))
                        self.assertIsNone(child.testzip())
                manifest = json.load(t.extractfile('release-manifest.json'))
                self.assertFalse(manifest['paymentEnabled'])
                self.assertFalse(manifest['scheduledTasksEnabled'])
                self.assertFalse(manifest['linuxRuntimeValidated'])
                self.assertNotIn(b'source-secret', t.extractfile('linux/config/examples/business-datasource.yml.example').read())

    def test_missing_artifact_fails_without_partial_archive(self):
        with tempfile.TemporaryDirectory() as d:
            base = Path(d)
            root = self.fixture(base)
            (root / 'wuye-b/MicroCommunity-v0.8/service-job/target/service-job.jar').unlink()
            target = base / 'release.tar.gz'
            with self.assertRaisesRegex(ValueError, 'Missing'):
                release.build(root, target)
            self.assertFalse(target.exists())

    def test_missing_static_runtime_dependency_fails_without_partial_archive(self):
        with tempfile.TemporaryDirectory() as d:
            base = Path(d)
            root = self.fixture(base)
            (root / 'wuye-f/MicroCommunityWeb-v0.8/lib/static-assets.cjs').unlink()
            target = base / 'release.tar.gz'
            with self.assertRaisesRegex(ValueError, 'Missing required artifact'):
                release.build(root, target)
            self.assertFalse(target.exists())

    def test_existing_output_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as d:
            base = Path(d)
            root = self.fixture(base)
            target = base / 'release.tar.gz'
            target.write_bytes(b'keep')
            with self.assertRaises(FileExistsError):
                release.build(root, target)
            self.assertEqual(target.read_bytes(), b'keep')

    def test_symlink_input_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            base = Path(d)
            root = self.fixture(base)
            source = root / 'wuye-f/MicroCommunityWeb-v0.8/public/leak.js'
            try:
                source.symlink_to(root / '.runtime/wechat-domain.json')
            except OSError:
                self.skipTest('Symlink creation unavailable')
            with self.assertRaisesRegex(ValueError, 'symlink'):
                release.build(root, base / 'release.tar.gz')

    def test_verify_rejects_tampering_unlisted_files_and_path_traversal(self):
        cases = [('index.html', b'changed', {'files': {'index.html': {'sha256': hashlib.sha256(b'original').hexdigest(), 'size': 8}}}),
                 ('unlisted.txt', b'unexpected', {'files': {}}),
                 ('../escape', b'escape', {'files': {}})]
        with tempfile.TemporaryDirectory() as d:
            for i, (name, data, manifest) in enumerate(cases):
                archive = Path(d) / f'{i}.tar.gz'
                with tarfile.open(archive, 'w:gz') as t:
                    for key, value in [(name, data), ('release-manifest.json', json.dumps(manifest).encode())]:
                        info = tarfile.TarInfo(key)
                        info.size = len(value)
                        t.addfile(info, io.BytesIO(value))
                with self.subTest(name=name), self.assertRaises(ValueError):
                    release.verify(archive)

    def test_sensitive_multiline_config_fails_closed(self):
        with self.assertRaisesRegex(ValueError, 'multiline'):
            release.sanitize_config('password: |\n  private-value\n', 'application.yml')

    def test_signed_project_jar_refuses_silent_signature_invalidation(self):
        with tempfile.TemporaryDirectory() as d:
            source, target = Path(d) / 'signed.jar', Path(d) / 'output.jar'
            source.write_bytes(jar_bytes({'META-INF/APP.SF': 'Signature-Version: 1.0\n',
                                         'BOOT-INF/classes/application.yml': 'password: private\n'}))
            with self.assertRaisesRegex(ValueError, 'Signed'):
                release.rewrite_jar(source, target)
            self.assertFalse(target.exists())

    def test_unhandled_xml_or_json_credentials_fail_closed(self):
        examples = {'settings.xml': '<property name="password" value="private-value"/>',
                    'settings.json': '{"appSecret":"private-value"}'}
        with tempfile.TemporaryDirectory() as d:
            for name, content in examples.items():
                source, target = Path(d) / name.replace('.', '_'), Path(d) / (name + '.out')
                source.write_bytes(jar_bytes({'BOOT-INF/classes/' + name: content}))
                with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'manual review'):
                    release.rewrite_jar(source, target)
                self.assertFalse(target.exists())

    def test_config_redaction_preserves_sharding_and_official_api_but_removes_original_hosts(self):
        text = ('url: jdbc:mysql://dev.db.java110.com:3306/TT\n'
                'defaultZone: http://192.0.2.1:8761/eureka/\n'
                'wx: https://api.weixin.qq.com/sns/oauth2/access_token\n'
                'algorithmExpression: ds${Long.parseLong(community_id) % 2}\n'
                'password: ""\n')
        result = release.sanitize_config(text, 'dataSource.yml')
        self.assertNotIn('dev.db.java110.com', result)
        self.assertNotIn('192.0.2.1', result)
        self.assertIn('https://api.weixin.qq.com/', result)
        self.assertIn('ds${Long.parseLong(community_id) % 2}', result)

    def test_verify_tree_detects_changed_extracted_file(self):
        with tempfile.TemporaryDirectory() as d:
            base = Path(d)
            root = self.fixture(base)
            target = base / 'release.tar.gz'
            release.build(root, target)
            extracted = base / 'extracted'
            extracted.mkdir()
            with tarfile.open(target) as t:
                for member in t.getmembers():
                    name = release.safe_name(member.name)
                    file = extracted / name
                    file.parent.mkdir(parents=True, exist_ok=True)
                    file.write_bytes(t.extractfile(member).read())
            self.assertEqual(release.verify_tree(extracted)['integrity'], 'passed')
            (extracted / 'h5/index.html').write_text('changed')
            with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
                release.verify_tree(extracted)


if __name__ == '__main__':
    unittest.main()
