"""Verify Central release artifacts and audit resolved runtime dependencies."""

import argparse
import json
import re
import subprocess
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def verify(root=ROOT, signed=False):
    """Check metadata, Java 17 bytecode, nonempty attachments, and signatures."""
    pom = ET.parse(root / "pom.xml").getroot()
    for field in ("name", "description", "url", "licenses/license/name",
                  "licenses/license/url", "developers/developer/id", "scm/url",
                  "scm/connection", "scm/developerConnection"):
        path = "/".join("m:" + part for part in field.split("/"))
        if not (pom.findtext(path, namespaces=NS) or "").strip():
            raise ValueError("Missing publication metadata: " + field)
    if pom.findtext("m:groupId", namespaces=NS) != "io.github.agentic-ai-java":
        raise ValueError("Unexpected release groupId")
    if pom.findtext("m:artifactId", namespaces=NS) != "connect":
        raise ValueError("Unexpected release artifactId")
    version = pom.findtext("m:version", namespaces=NS) or ""
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?", version) or version.endswith("-SNAPSHOT"):
        raise ValueError("Release version must be concrete and non-SNAPSHOT")
    base = "connect-" + version
    payloads = [(root / "pom.xml", root / "target" / (base + ".pom.asc"))]
    for suffix in ("", "-sources", "-javadoc"):
        jar = root / "target" / (base + suffix + ".jar")
        with ZipFile(jar) as archive:
            names = archive.namelist()
            if not names or archive.testzip() is not None:
                raise ValueError("Invalid release JAR: " + jar.name)
            extension = ".java" if suffix == "-sources" else ".html" if suffix else ".class"
            if not any(name.startswith("io/github/agenticai/connect/") and name.endswith(extension) for name in names):
                raise ValueError("Missing release content: " + jar.name)
            if not suffix:
                for name in names:
                    if name.endswith(".class"):
                        data = archive.read(name)[:8]
                        if len(data) != 8 or data[:4] != b"\xca\xfe\xba\xbe" or int.from_bytes(data[6:8], "big") > 61:
                            raise ValueError("Class is not compatible with Java 17: " + name)
        payloads.append((jar, jar.with_suffix(".jar.asc")))
    if signed:
        for payload, signature in payloads:
            if not signature.is_file():
                raise ValueError("Missing signature: " + signature.name)
            result = subprocess.run(["gpg", "--batch", "--verify", str(signature), str(payload)], capture_output=True)
            if result.returncode:
                raise ValueError("Invalid signature: " + signature.name)
    # Preserve the exact signed POM alongside GitHub release assets.
    (root / "target" / (base + ".pom")).write_bytes((root / "pom.xml").read_bytes())
    print("Release artifacts verified: " + base + (" (signed)" if signed else " (unsigned)"))


def runtime_coordinates(tree):
    """Enumerate Maven's resolved runtime tree rather than hardcoding dependencies."""
    coordinates = set()

    def visit(node, project=False):
        if not project:
            if node.get("scope") not in ("compile", "runtime"):
                return
            coordinate = (node["groupId"] + ":" + node["artifactId"], node["version"])
            if not all(coordinate) or "${" in coordinate[1]:
                raise ValueError("Unresolved runtime dependency")
            coordinates.add(coordinate)
        for child in node.get("children", []):
            visit(child)

    visit(tree, project=True)
    return sorted(coordinates)


def query_osv(name, version):
    """Retry rate limits after 20 seconds and transient failures once after 2 seconds."""
    body = json.dumps({"package": {"name": name, "ecosystem": "Maven"}, "version": version}).encode()
    for attempt in range(2):
        try:
            request = urllib.request.Request("https://api.osv.dev/v1/query", data=body,
                                             headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(request, timeout=30) as response:
                result = json.load(response)
            if not isinstance(result, dict) or "error" in result or not isinstance(result.get("vulns", []), list):
                raise ValueError("Invalid OSV response")
            return result
        except urllib.error.HTTPError as exc:
            if attempt == 0 and (exc.code == 429 or exc.code >= 500):
                time.sleep(20 if exc.code == 429 else 2)
                continue
            raise RuntimeError("OSV audit failed with HTTP " + str(exc.code)) from None
        except (urllib.error.URLError, TimeoutError):
            if attempt == 0:
                time.sleep(2)
                continue
            raise RuntimeError("OSV audit unavailable; publication stopped") from None


def audit(tree_path=ROOT / "target/runtime-dependencies.json"):
    """Stop publication on any registered, non-withdrawn runtime vulnerability."""
    tree = json.loads(tree_path.read_text(encoding="utf-8"))
    for name, version in runtime_coordinates(tree):
        findings = [v["id"] for v in query_osv(name, version).get("vulns", []) if not v.get("withdrawn")]
        if findings:
            raise ValueError("Runtime dependency vulnerabilities: " + name + ":" + version + " " + ", ".join(findings))
        print("No registered OSV vulnerabilities: " + name + ":" + version)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("verify", "audit"))
    parser.add_argument("--signed", action="store_true")
    args = parser.parse_args()
    if args.command == "verify":
        verify(signed=args.signed)
    else:
        audit()


if __name__ == "__main__":
    main()
