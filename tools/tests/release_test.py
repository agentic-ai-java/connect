"""Regression tests for publication checks; no credentials or network required."""

import importlib.util
import json
import tempfile
import unittest
import urllib.error
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest.mock import patch
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("release", ROOT / "tools/release.py")
release = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release)


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        (ROOT / "target").mkdir(exist_ok=True)
        self.directory = tempfile.TemporaryDirectory(dir=ROOT / "target")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        (self.root / "target").mkdir()
        # Keep fixture coordinates stable when the actual project version changes.
        pom = ET.parse(ROOT / "pom.xml")
        pom.getroot().find("m:version", release.NS).text = "0.1.0"
        ET.register_namespace("", release.NS["m"])
        pom.write(self.root / "pom.xml", encoding="utf-8", xml_declaration=True)
        for suffix, name, data in (("", "Connect.class", b"\xca\xfe\xba\xbe\x00\x00\x00\x3d"),
                                   ("-sources", "Connect.java", b"class Connect {}"),
                                   ("-javadoc", "Connect.html", b"<html></html>")):
            with ZipFile(self.root / "target" / ("connect-0.1.0" + suffix + ".jar"), "w") as jar:
                jar.writestr("io/github/agenticai/connect/" + name, data)

    def test_valid_artifacts(self):
        release.verify(self.root)
        self.assertEqual((self.root / "pom.xml").read_bytes(), (self.root / "target/connect-0.1.0.pom").read_bytes())

    def test_invalid_metadata_and_version(self):
        original = (self.root / "pom.xml").read_text()
        for old, new in (("<name>connect</name>", ""), ("<version>0.1.0</version>", "<version>0.1.0-SNAPSHOT</version>"),
                         ("<version>0.1.0</version>", "<version>${revision}</version>"),
                         ("<artifactId>connect</artifactId>", "<artifactId>other</artifactId>"),
                         ("<groupId>io.github.agentic-ai-java</groupId>", "<groupId>other</groupId>")):
            with self.subTest(new=new):
                (self.root / "pom.xml").write_text(original.replace(old, new), encoding="utf-8")
                with self.assertRaises(ValueError):
                    release.verify(self.root)

    def test_invalid_bytecode(self):
        for data in (b"\xca\xfe\xba\xbe\x00\x00\x00\x41", b"bad"):
            with ZipFile(self.root / "target/connect-0.1.0.jar", "w") as jar:
                jar.writestr("io/github/agenticai/connect/Connect.class", data)
            with self.assertRaises(ValueError):
                release.verify(self.root)

    def test_empty_attachment(self):
        with ZipFile(self.root / "target/connect-0.1.0-sources.jar", "w"):
            pass
        with self.assertRaises(ValueError):
            release.verify(self.root)

    def test_missing_content(self):
        with ZipFile(self.root / "target/connect-0.1.0-sources.jar", "w") as jar:
            jar.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0")
        with self.assertRaises(ValueError):
            release.verify(self.root)

    def test_missing_signature(self):
        with self.assertRaisesRegex(ValueError, "Missing signature"):
            release.verify(self.root, signed=True)

    def test_signed_artifacts(self):
        for suffix in (".pom", ".jar", "-sources.jar", "-javadoc.jar"):
            (self.root / "target" / ("connect-0.1.0" + suffix + ".asc")).write_text("signature")
        with patch.object(release.subprocess, "run") as run:
            run.return_value.returncode = 0
            release.verify(self.root, signed=True)
            self.assertEqual(run.call_count, 4)
            run.return_value.returncode = 1
            with self.assertRaisesRegex(ValueError, "Invalid signature"):
                release.verify(self.root, signed=True)

    def test_resolved_runtime_tree(self):
        child = {"groupId": "com.google.code.gson", "artifactId": "gson", "version": "2.13.2", "scope": "compile"}
        tree = {"children": [child, child, dict(child, scope="test"), dict(child, scope="provided")]}
        self.assertEqual(release.runtime_coordinates(tree), [("com.google.code.gson:gson", "2.13.2")])
        with self.assertRaises(ValueError):
            release.runtime_coordinates({"children": [dict(child, version="${revision}")]})

    def test_audit_rejects_findings(self):
        path = self.root / "tree.json"
        path.write_text(json.dumps({"children": [{"groupId": "g", "artifactId": "a", "version": "1", "scope": "runtime"}]}))
        with patch.object(release, "query_osv", return_value={"vulns": [{"id": "GHSA-test"}]}) as query:
            with self.assertRaisesRegex(ValueError, "GHSA-test"):
                release.audit(path)
            query.assert_called_once_with("g:a", "1")
            query.return_value = {"vulns": [{"id": "GHSA-test", "withdrawn": "2026-01-01"}]}
            release.audit(path)

    def test_rate_limit_backoff(self):
        error = urllib.error.HTTPError("https://api.osv.dev", 429, "limited", {}, None)
        with patch.object(release.urllib.request, "urlopen", side_effect=[error, error]) as request, patch.object(release.time, "sleep") as sleep:
            with self.assertRaisesRegex(RuntimeError, "429"):
                release.query_osv("g:a", "1")
            sleep.assert_called_once_with(20)
            self.assertEqual(request.call_count, 2)

    def test_transient_backoff(self):
        for error in (TimeoutError(), urllib.error.URLError("offline"),
                      urllib.error.HTTPError("https://api.osv.dev", 503, "unavailable", {}, None)):
            with patch.object(release.urllib.request, "urlopen", side_effect=error) as request, patch.object(release.time, "sleep") as sleep:
                with self.assertRaises(RuntimeError):
                    release.query_osv("g:a", "1")
                sleep.assert_called_once_with(2)
                self.assertEqual(request.call_count, 2)

    def test_permanent_http_failure(self):
        error = urllib.error.HTTPError("https://api.osv.dev", 400, "invalid", {}, None)
        with patch.object(release.urllib.request, "urlopen", side_effect=error) as request:
            with self.assertRaisesRegex(RuntimeError, "400"):
                release.query_osv("g:a", "1")
            self.assertEqual(request.call_count, 1)

    def test_invalid_osv_response(self):
        for response in ([], {"error": "bad"}, {"vulns": {}}):
            with patch.object(release.urllib.request, "urlopen"), patch.object(release.json, "load", return_value=response):
                with self.assertRaises(ValueError):
                    release.query_osv("g:a", "1")

    def test_valid_osv_response(self):
        with patch.object(release.urllib.request, "urlopen"), patch.object(release.json, "load", return_value={}):
            self.assertEqual(release.query_osv("g:a", "1"), {})


if __name__ == "__main__":
    unittest.main()
