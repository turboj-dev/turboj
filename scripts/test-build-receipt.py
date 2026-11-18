import importlib.util
from pathlib import Path
import json
import os
import shutil
import stat
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('receipt', Path(__file__).with_name('build-receipt.py'))
receipt = importlib.util.module_from_spec(spec)
spec.loader.exec_module(receipt)

class ReceiptTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = self.root / 'app/src/main/java/Example.java'
        self.source.parent.mkdir(parents=True)
        self.source.write_text('class Example {}')
        self.artifact = self.root / 'app/target/turboj-app.jar'
        self.artifact.parent.mkdir()
        self.artifact.write_bytes(b'controlled artifact')
        self.data = {'schemaVersion': 1, 'artifact': 'app/target/turboj-app.jar',
                     'sha256': receipt.digest(self.artifact), 'sources': receipt.inputs(self.root)}

    def test_valid_and_ignored_personal_examples(self):
        (self.root/'examples').mkdir()
        (self.root/'examples/Personal.java').write_text('personal changes')
        receipt.verify(self.root, self.data)

    def test_source_modified_added_removed(self):
        self.source.write_text('class Example { int changed; }')
        with self.assertRaises(ValueError): receipt.verify(self.root, self.data)
        self.source.write_text('class Example {}')
        extra = self.source.with_name('Other.java')
        extra.write_text('class Other {}')
        with self.assertRaises(ValueError): receipt.verify(self.root, self.data)
        extra.unlink(); self.source.unlink()
        with self.assertRaises(ValueError): receipt.verify(self.root, self.data)

    def test_artifact_replacement(self):
        self.artifact.write_bytes(b'other build')
        with self.assertRaises(ValueError): receipt.verify(self.root, self.data)

    def test_no_escape_or_unknown_schema(self):
        self.data['artifact'] = '../outside.jar'
        with self.assertRaises(ValueError): receipt.verify(self.root, self.data)
        self.data['schemaVersion'] = 2
        with self.assertRaises(ValueError): receipt.verify(self.root, self.data)

    def test_java_home_is_effective_build_runtime(self):
        self.assertEqual('/owned/project-jdk/bin/java', receipt.effective_java({'JAVA_HOME': '/owned/project-jdk', 'PATH': '/other/jdk/bin'}))

    def test_packaged_jar_must_match_before_runtime_capture(self):
        bundle = self.root / 'TurboJ.app'
        jar = bundle / 'Contents/app/turboj-app.jar'
        jar.parent.mkdir(parents=True)
        jar.write_bytes(b'different artifact')
        with self.assertRaisesRegex(ValueError, 'Packaged JAR'):
            receipt.package_files(bundle, self.data)

    def bundle(self):
        bundle = self.root / 'TurboJ.app'
        names = ('Contents/app/TurboJ.cfg', 'Contents/Info.plist', 'Contents/MacOS/TurboJ',
                 'Contents/runtime/Contents/Home/bin/java', 'Contents/runtime/Contents/Home/lib/modules',
                 'Contents/runtime/Contents/Home/release', 'Contents/_CodeSignature/CodeResources')
        for name in names:
            file = bundle / name
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text('controlled fixture: ' + name)
        jar = bundle / 'Contents/app/turboj-app.jar'
        jar.write_bytes(self.artifact.read_bytes())
        build = bundle / 'Contents/Resources/build-receipt.json'
        build.parent.mkdir(parents=True)
        build.write_text(json.dumps(self.data))
        link = bundle / 'Contents/app/current.jar'
        link.symlink_to('turboj-app.jar')
        return bundle, build

    def package_data(self, bundle, build):
        return {'schemaVersion': 2, 'buildReceiptSha256': receipt.digest(build),
                **receipt.package_inventory(bundle)}

    def test_external_receipt_does_not_mutate_the_sealed_bundle(self):
        bundle, build = self.bundle()
        before = receipt.package_inventory(bundle)
        output = self.root / 'build-receipt.json'
        output.write_text(build.read_text())
        with patch.object(receipt.subprocess, 'check_output', return_value='controlled Java version'):
            value = receipt.capture_package(bundle, output, self.root / 'jdk')
        destination = receipt.package_receipt_path(bundle)
        self.assertEqual(self.root / 'TurboJ.package-receipt.json', destination)
        destination.write_text(json.dumps(value))
        self.assertEqual(before, receipt.package_inventory(bundle))
        receipt.verify_package(bundle, value)

    def test_installed_copy_has_the_exact_same_identity(self):
        bundle, build = self.bundle()
        data = self.package_data(bundle, build)
        installed = self.root / 'Installed.app'
        shutil.copytree(bundle, installed, symlinks=True)
        receipt.verify_package(installed, data)
        launcher = installed / 'Contents/MacOS/TurboJ'
        launcher.write_bytes(launcher.read_bytes() + b' re-signed')
        with self.assertRaisesRegex(ValueError, 'files differ'):
            receipt.verify_package(installed, data)

    def test_added_removed_changed_modes_and_links_are_detected(self):
        bundle, build = self.bundle()
        data = self.package_data(bundle, build)
        extra = bundle / 'Contents/Resources/late-receipt.json'
        extra.write_text('{}')
        with self.assertRaises(ValueError): receipt.verify_package(bundle, data)
        extra.unlink()
        launcher = bundle / 'Contents/MacOS/TurboJ'
        previous = stat.S_IMODE(launcher.stat().st_mode)
        launcher.chmod(previous ^ stat.S_IXUSR)
        with self.assertRaisesRegex(ValueError, 'modes differ'): receipt.verify_package(bundle, data)
        launcher.chmod(previous)
        link = bundle / 'Contents/app/current.jar'
        link.unlink(); link.symlink_to('TurboJ.cfg')
        with self.assertRaisesRegex(ValueError, 'links differ'): receipt.verify_package(bundle, data)
        link.unlink(); link.symlink_to('/tmp')
        with self.assertRaisesRegex(ValueError, 'escapes'): receipt.verify_package(bundle, data)
        link.unlink(); link.symlink_to('turboj-app.jar')
        launcher.unlink()
        with self.assertRaisesRegex(ValueError, 'files differ'): receipt.verify_package(bundle, data)

    def test_old_in_bundle_receipt_schema_is_not_certified(self):
        bundle, build = self.bundle()
        data = self.package_data(bundle, build)
        data['schemaVersion'] = 1
        with self.assertRaisesRegex(ValueError, 'Unsupported'):
            receipt.verify_package(bundle, data)

if __name__ == '__main__': unittest.main()
