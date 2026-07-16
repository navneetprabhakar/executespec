import os
import pathlib
import stat
import tempfile
import unittest
import zipfile

from importlib.util import module_from_spec, spec_from_file_location


ROOT = pathlib.Path(__file__).resolve().parent
SPEC = spec_from_file_location("build_eval_safe_extract", ROOT / "build-eval-safe-extract.py")
MODULE = module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class SafeExtractTest(unittest.TestCase):
    def make_zip(self, entries):
        handle = tempfile.NamedTemporaryFile(suffix=".zip", delete=False)
        handle.close()
        with zipfile.ZipFile(handle.name, "w") as archive:
            for info, content in entries:
                archive.writestr(info, content)
        return pathlib.Path(handle.name)

    def test_valid_archive_extracts(self):
        archive = self.make_zip([("src/main.py", "print('ok')")])
        with tempfile.TemporaryDirectory() as directory:
            destination = pathlib.Path(directory) / "workspace"
            MODULE.extract(archive, destination)
            self.assertEqual((destination / "src/main.py").read_text(), "print('ok')")
        archive.unlink()

    def test_parent_traversal_is_rejected(self):
        archive = self.make_zip([("../escaped.txt", "bad")])
        with tempfile.TemporaryDirectory() as directory:
            destination = pathlib.Path(directory) / "workspace"
            with self.assertRaisesRegex(ValueError, "unsafe archive path"):
                MODULE.extract(archive, destination)
            self.assertFalse((pathlib.Path(directory) / "escaped.txt").exists())
        archive.unlink()

    def test_absolute_path_is_rejected(self):
        archive = self.make_zip([("/tmp/escaped.txt", "bad")])
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "unsafe archive path"):
                MODULE.extract(archive, pathlib.Path(directory) / "workspace")
        archive.unlink()

    def test_symlink_entry_is_rejected(self):
        info = zipfile.ZipInfo("link")
        info.external_attr = (stat.S_IFLNK | 0o777) << 16
        archive = self.make_zip([(info, "../../escaped")])
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, "symlink archive entry"):
                MODULE.extract(archive, pathlib.Path(directory) / "workspace")
        archive.unlink()


if __name__ == "__main__":
    unittest.main()
