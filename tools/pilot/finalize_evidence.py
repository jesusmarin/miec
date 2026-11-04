"""Completes and seals a successful local dossier without modifying application data."""
import argparse
import csv
import datetime as dt
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile


def require(condition, message):
    """Keeps integrity checks enabled even when the operator uses Python optimization."""
    if not condition:
        raise ValueError(message)


def digest(path):
    """Calculates SHA-256 without loading complete JARs or database dumps into memory."""
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def finalize(folder, maven):
    """Verifies the accepted cases, records tool versions and inventories retained evidence."""
    manifest = json.loads((folder / "manifest.json").read_text(encoding="utf-8"))
    with (folder / "cases.csv").open(newline="", encoding="utf-8") as stream:
        cases = list(csv.DictReader(stream))
    require(manifest["technicalAcceptance"] == "APROBADO", "Only successful dossiers can be sealed")
    require({row["id"] for row in cases} == {"LP-" + str(index).zfill(2) for index in range(1, 13)}, "Twelve explicit case identities are required")
    require(all(row["status"] == "APROBADO" for row in cases), "Pending or failed cases cannot be sealed as accepted")
    for label, entry in manifest["artifacts"].items():
        artifact = folder / entry["path"]
        require(digest(artifact) == entry["sha256"], "Artifact changed after acceptance")
        expected = {"miec": "miec-prototype", "petclinic": "spring-petclinic", "sdk": "miec-recipient-sdk"}[label]
        with zipfile.ZipFile(artifact) as archive:
            names = [name for name in archive.namelist() if name.endswith("/pom.properties") and "/" + expected + "/" in name]
            require(len(names) == 1, "Expected one main artifact identity")
            entry["mavenCoordinates"] = dict(line.split("=", 1) for line in archive.read(names[0]).decode().splitlines() if "=" in line and not line.startswith("#"))
            if label in ("miec", "petclinic"):
                name, variable = ("application-postgres.properties", "MIEC_POSTGRES_PASSWORD") if label == "miec" else ("application-miec-postgres.properties", "MIEC_PETCLINIC_POSTGRES_PASSWORD")
                profile = archive.read("BOOT-INF/classes/" + name)
                match = re.search(r"(?m)^spring.datasource.password=(.*)$", profile.decode())
                require(match and match[1].strip() in ("${" + variable + ":xxx}", "${" + variable + "}"), "Private database defaults cannot be exported")
                destination = folder / "sources/packaged-config" / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(profile)
                entry["packagedProfile"] = destination.relative_to(folder).as_posix()
    for entry in manifest["backups"].values():
        require(digest(folder / entry["file"]) == entry["sha256"], "Backup changed after restoration")
    version = subprocess.run([str(maven), "--version"], capture_output=True, text=True, encoding="utf-8", errors="replace", check=True)
    (folder / "maven-version.txt").write_text(version.stdout + version.stderr, encoding="utf-8")
    manifest["buildTools"] = {"mavenVersionEvidence": "maven-version.txt", "javaVersionEvidence": "java-version.txt", "python": sys.version}
    manifest["caseEvidence"] = {}
    for row in cases:
        files = sorted((folder / "responses").glob("*-" + row["id"] + ".json"))
        require(files, "Each case must retain actual HTTP evidence")
        row["evidenceLastWrittenAtUtc"] = dt.datetime.fromtimestamp(max(path.stat().st_mtime for path in files), dt.timezone.utc).isoformat()
        manifest["caseEvidence"][row["id"]] = [path.relative_to(folder).as_posix() for path in files]
    with (folder / "cases.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=["id", "case", "status", "result", "evidenceLastWrittenAtUtc"])
        writer.writeheader()
        writer.writerows(cases)
    collector = folder / "harness/finalize_evidence.py"
    shutil.copyfile(Path(__file__), collector)
    manifest["evidenceCompletion"] = {"collector": "harness/finalize_evidence.py", "sha256": digest(collector),
                                      "artifactAndBackupHashesRechecked": True, "completedAtUtc": dt.datetime.now(dt.timezone.utc).isoformat(),
                                      "caseTimestamps": "Last case HTTP evidence file write time, not an inferred business commit time"}
    (folder / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    entries = {path.relative_to(folder).as_posix(): {"sha256": digest(path), "bytes": path.stat().st_size}
               for path in sorted(folder.rglob("*")) if path.is_file() and path.name not in ("inventory.json", "inventory-sha256.txt")}
    inventory = folder / "inventory.json"
    inventory.write_text(json.dumps(entries, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (folder / "inventory-sha256.txt").write_text(digest(inventory) + "  inventory.json\n", encoding="ascii")
    print("DOSSIER VERIFIED: 12 approved cases; 3 JARs and 2 backups unchanged; " + str(len(entries)) + " evidence files inventoried.")
    print("INVENTORY SHA256=" + digest(inventory))


def main():
    """Requires an explicit completed dossier and Maven executable, never database credentials."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("folder", type=Path)
    parser.add_argument("--maven-executable", type=Path, required=True)
    args = parser.parse_args()
    finalize(args.folder.resolve(strict=True), args.maven_executable.resolve(strict=True))


if __name__ == "__main__":
    main()
