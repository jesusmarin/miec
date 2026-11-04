"""Opt-in MVP-08 acceptance against real packaged applications and local PostgreSQL.

Business commands use HTTP. SQL observes effects, checks identity and creates/restores
new backup databases; it never fabricates MIEC states, permissions or conclusions.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import csv
import datetime as dt
import hashlib
import io
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT = Path(__file__).resolve().parents[2]
DESTINATION = "petclinic-experiment-001"
METHOD = "MVP08-LOCAL-COUNT-V1"
CASES = {
    "LP-01": "Configuracion, artefactos y datos propios",
    "LP-02": "Primer plan autorizado y finalizacion E08",
    "LP-03": "Consulta repetida sin efectos",
    "LP-04": "Concurrencia y replay con una sola visita",
    "LP-05": "Conflicto de contenido conserva resultado",
    "LP-06": "Validacion de negocio, huella y transporte",
    "LP-07": "Respuesta perdida posterior al commit y reconciliacion",
    "LP-08": "Reinicio independiente de ambas aplicaciones",
    "LP-09": "Continuidad autorizada y segunda ronda",
    "LP-10": "Dos rondas y cierre E14 conservador",
    "LP-11": "Roles, versiones y referencias ajenas",
    "LP-12": "Respaldo y restauracion del par en bases nuevas",
}


def require(condition, message):
    """Stops acceptance on the first failed invariant, without retrying effects."""
    if not condition:
        raise AssertionError(message)


def sha256(path):
    """Hashes a fixed artifact or backup without interpreting its contents."""
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def sql_text(value):
    """Quotes a controlled SQL value for psql stdin; no shell evaluates SQL text."""
    text = str(value)
    require("\x00" not in text, "NUL is not a PostgreSQL text value")
    return "'" + text.replace("'", "''") + "'"


def property_value(path, key):
    """Reads one local Java property and resolves its environment placeholder privately."""
    matches = re.findall(r"^" + re.escape(key) + r"\s*[=:]\s*(.*)$", path.read_text(encoding="utf-8"), re.M)
    require(len(matches) == 1, "Missing or ambiguous local database configuration")
    value = re.sub(r"\\(u[0-9a-fA-F]{4}|.)", lambda m: chr(int(m[1][1:], 16)) if m[1].startswith("u") else {"n": "\n", "r": "\r", "t": "\t", "f": "\f"}.get(m[1], m[1]), matches[0])
    match = re.fullmatch(r"\$\{([^:}]+)(?::(.*))?\}", value)
    return os.environ.get(match[1], match[2] or "") if match else value


class Database:
    """Keeps JDBC secrets private and validates the explicit loopback lab connection."""
    def __init__(self, profile, expected):
        """Loads credentials only from the user's local configuration or environment."""
        self.url = property_value(profile, "spring.datasource.url")
        self.user = property_value(profile, "spring.datasource.username")
        self.password = property_value(profile, "spring.datasource.password")
        parsed = urllib.parse.urlsplit(self.url.removeprefix("jdbc:"))
        require(parsed.scheme == "postgresql" and parsed.hostname in ("127.0.0.1", "localhost") and parsed.port == 5532, "Pilot requires the configured local PostgreSQL port 5532")
        require(not parsed.username and not parsed.password and "password=" not in self.url and "user=" not in self.url, "Credentials in JDBC URL are forbidden")
        require(parsed.path == "/" + expected and self.user == "postgres", "Unexpected local database or SQL role")
        require(self.password and self.password != "xxx", "Configure the local PostgreSQL credential before running the pilot")
        self.name = expected

    def environment(self, name=None):
        """Returns private child-process overrides; callers never serialize this mapping."""
        url = self.url if name is None else "jdbc:postgresql://127.0.0.1:5532/" + name + "?currentSchema=public"
        return {"SPRING_DATASOURCE_URL": url, "SPRING_DATASOURCE_USERNAME": self.user, "SPRING_DATASOURCE_PASSWORD": self.password}


class Pilot:
    """Owns an append-only evidence directory and only the JVMs it starts."""
    def __init__(self, args):
        """Creates a fresh durable dossier outside the source and build directories."""
        self.args = args
        self.run_id = "MVP08-" + dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
        self.folder = args.evidence_root.resolve() / self.run_id
        require(not self.folder.is_relative_to(ROOT) and not self.folder.is_relative_to(ROOT.parent / "spring-petclinic"), "Evidence must live outside repositories and target")
        self.folder.mkdir(parents=True, exist_ok=False)
        self.mdb = Database(ROOT / "src/main/resources/application-postgres.properties", "miec-v01")
        self.pdb = Database(ROOT.parent / "spring-petclinic/src/main/resources/application-miec-postgres.properties", "petclinic")
        self.miec_db, self.pet_db = self.mdb.name, self.pdb.name
        self.author, self.approver = "pilot-author", "pilot-approver"
        self.author_password, self.approver_password, self.api_key = secrets.token_hex(24), secrets.token_hex(24), secrets.token_hex(32)
        self.case = "LP-01"
        self.cases = {key: {"id": key, "case": title, "status": "PENDIENTE", "result": ""} for key, title in CASES.items()}
        self.lock, self.event = threading.Lock(), 0
        self.processes, self.generations = {}, {"miec": 0, "petclinic": 0}
        self.miec_port, self.pet_port = self.free_port(), self.free_port()
        self.base, self.pet_base = f"http://127.0.0.1:{self.miec_port}", f"http://127.0.0.1:{self.pet_port}"
        self.rounds, self.fault_keys, self.drop_post = [], set(), False
        self.manifest = {"contract": "MVP08-LOCAL-02", "runId": self.run_id, "operator": "automated local acceptance", "humanIndependentApproval": False,
                         "databases": {"miec": self.miec_db, "petclinic": self.pet_db}, "accounts": {"AUTHOR": self.author, "APPROVER": self.approver},
                         "transport": "loopback HTTP; controlled forwarding proxy", "caseResults": "cases.csv", "restoredDatabases": {}, "processes": []}

    @staticmethod
    def free_port():
        """Obtains a currently free loopback port without reusing a user's server."""
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0))
            return listener.getsockname()[1]

    def write(self, relative, value):
        """Persists an explicitly selected non-secret evidence object."""
        path = self.folder / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    def flush(self):
        """Updates the technical act and case states; it never claims human sign-off."""
        self.manifest["rounds"] = self.rounds
        self.write("manifest.json", self.manifest)
        with (self.folder / "cases.csv").open("w", newline="", encoding="utf-8") as stream:
            writer = csv.DictWriter(stream, fieldnames=["id", "case", "status", "result"])
            writer.writeheader()
            writer.writerows(self.cases.values())

    def passed(self, case, result):
        """Records a case only after all its assertions and raw evidence exist."""
        self.cases[case].update(status="APROBADO", result=result)
        self.flush()
        print(f"{case} APROBADO: {result}", flush=True)

    def command(self, args, input=None, timeout=60):
        """Runs a fixed argument vector without a shell or credential-bearing arguments."""
        result = subprocess.run([str(x) for x in args], input=input, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout,
                                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        require(result.returncode == 0, "Local command failed: " + str(args[0]) + "; " + result.stderr[-1800:])
        return result.stdout.strip()

    def sql(self, database, statement, evidence=None):
        """Runs controlled SQL through psql stdin and optionally retains its non-secret result."""
        output = self.command(["docker", "exec", "-i", self.args.postgres_container, "psql", "-X", "-w", "-U", "postgres", "-d", database, "-v", "ON_ERROR_STOP=1", "-tA"], statement)
        if evidence:
            self.write(evidence, {"database": database, "sql": statement, "output": output})
        return output

    def now(self):
        """Observes the authoritative server clock rather than inventing past client timestamps."""
        return self.sql(self.miec_db, "SELECT to_char(clock_timestamp() AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.US\"Z\"');")

    def observation(self, marker, key=None, evidence=None):
        """Measures the exact business marker and operation key in one server-side observation."""
        operation = "0" if key is None else f"(SELECT count(*) FROM miec_visit_operations WHERE operation_key={sql_text(uuid.UUID(key))}::uuid)"
        query = f"SELECT json_build_object('observedAt',clock_timestamp(),'V',(SELECT count(*) FROM visits WHERE pet_id={self.pet_id} AND visit_date={sql_text(self.visit_date)}::date AND description={sql_text(marker)}),'K',{operation});"
        value = json.loads(self.sql(self.pet_db, query))
        value["observedAt"] = value["observedAt"].replace("+00:00", "Z")
        if evidence:
            self.write(evidence, {"database": self.pet_db, "sql": query, "observation": value, "method": METHOD, "value": max(0, value["V"] - 1)})
        return value

    def http(self, base, path, body=None, role="author", expected=None, headers=None, record=True, raw=None):
        """Sends real HTTP and archives only method/path/body/status; authentication is never logged."""
        import base64
        payload = raw if raw is not None else None if body is None else json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode()
        selected = dict(headers or {})
        if base == self.base and role:
            account, password = (self.author, self.author_password) if role == "author" else (self.approver, self.approver_password)
            selected["Authorization"] = "Basic " + base64.b64encode((account + ":" + password).encode()).decode()
        if payload is not None:
            selected.setdefault("Content-Type", "application/json")
        request = urllib.request.Request(base + path, data=payload, headers=selected, method="GET" if payload is None else "POST")
        opener = urllib.request.build_opener(NoRedirect)
        try:
            response = opener.open(request, timeout=50)
        except urllib.error.HTTPError as rejected:
            response = rejected
        with response:
            status, content, location = response.code, response.read(), response.headers.get("Location")
        try:
            decoded = json.loads(content) if content else None
        except (ValueError, UnicodeDecodeError):
            decoded = content.decode("utf-8", errors="replace")
        if record:
            with self.lock:
                self.event += 1
                event = self.event
            stem = f"{event:04d}-{self.case}"
            self.write(f"responses/{stem}.json", {"path": path, "status": status, "location": location, "body": decoded})
            if payload is not None:
                target = self.folder / "requests" / (stem + ".body")
                target.parent.mkdir(exist_ok=True)
                target.write_bytes(payload)
        if expected is not None:
            allowed = [expected] if isinstance(expected, int) else expected
            require(status in allowed, f"HTTP {path}: expected {allowed}, got {status}: {str(decoded)[:1500]}")
        return status, decoded, location

    def get(self, path, role="author"):
        """Reads an authenticated MIEC resource without altering domain state."""
        return self.http(self.base, path, role=role, expected=200)[1]

    def post(self, path, body, role="author", expected=200):
        """Invokes a versioned domain command using the real application's public API."""
        return self.http(self.base, path, body, role, expected)[1]

    def version(self, cycle):
        """Always obtains the current cycle version instead of guessing the next number."""
        return self.get("/api/cycles/" + cycle)["version"]

    def start(self, kind, restored=False):
        """Starts one packaged application with private environment credentials and fixed pilot ports."""
        self.generations[kind] += 1
        generation = self.generations[kind]
        if kind == "miec":
            jar, port, profiles, database = self.miec_jar, self.miec_port, "postgres", self.mdb
            private = {"MIEC_AUTHOR_USERNAME": self.author, "MIEC_AUTHOR_PASSWORD": self.author_password, "MIEC_APPROVER_USERNAME": self.approver,
                       "MIEC_APPROVER_PASSWORD": self.approver_password, "MIEC_RECIPIENT_ENABLED": "true", "MIEC_RECIPIENT_ADAPTER_ID": "http-recipient-v1",
                       "MIEC_RECIPIENT_ADAPTER_VERSION": "1", "MIEC_RECIPIENT_DESTINATION_ID": DESTINATION, "MIEC_RECIPIENT_BASE_URL": self.proxy_base,
                       "MIEC_RECIPIENT_API_KEY": self.api_key, "MIEC_RECIPIENT_EXECUTE_PATH": "/miec/visits", "MIEC_RECIPIENT_TIMEOUT": "PT5S"}
            dbname = self.miec_db
        else:
            jar, port, profiles, database = self.petclinic_jar, self.pet_port, "miec,miec-postgres", self.pdb
            private = {"MIEC_PETCLINIC_API_KEY": self.api_key, "MIEC_PETCLINIC_DESTINATION": DESTINATION}
            dbname = self.pet_db
        environment = os.environ.copy()
        environment.update(database.environment(dbname))
        environment.update(private)
        arguments = [str(self.args.java), "-Duser.timezone=America/Bogota", "-jar", str(jar), "--spring.profiles.active=" + profiles,
                     "--server.address=127.0.0.1", "--server.port=" + str(port), "--spring.main.banner-mode=off", "--logging.level.root=WARN",
                     "--spring.application.admin.enabled=true", "--spring.jmx.enabled=true"]
        if kind == "miec":
            arguments += ["--miec.execution.lease-duration=PT5S", "--miec.execution.heartbeat-interval=PT1S"]
        log_path = self.folder / "logs" / f"{kind}-{generation}.log"
        log_path.parent.mkdir(exist_ok=True)
        with log_path.open("wb") as log:
            process = subprocess.Popen(arguments, cwd=self.folder, env=environment, stdout=log, stderr=subprocess.STDOUT,
                                       creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
        self.processes[kind] = process
        self.manifest["processes"].append({"application": kind, "generation": generation, "pid": process.pid, "database": dbname, "restored": restored})
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline and process.poll() is None:
            try:
                if kind == "miec":
                    status, _, _ = self.http(self.base, "/api/systems/" + str(uuid.uuid4()), expected=None, record=False)
                    if status == 404:
                        self.flush()
                        return
                elif urllib.request.urlopen(self.pet_base + "/actuator/health", timeout=2).status == 200:
                    self.flush()
                    return
            except (urllib.error.URLError, TimeoutError, ConnectionError):
                pass
            time.sleep(0.2)
        raise AssertionError(f"{kind} failed to become available; inspect {log_path}")

    def stop(self, kind):
        """Closes only a JVM started by this pilot, before checkpoint or restart."""
        process = self.processes.get(kind)
        if process is not None and process.poll() is None:
            self.command([self.args.java, "--add-modules", "jdk.attach", ROOT / "tools/pilot/PilotShutdown.java", str(process.pid)], timeout=25)
            process.wait(timeout=25)
        self.processes.pop(kind, None)

    def fingerprint(self, body):
        """Delegates probe fingerprinting to the actual shared SDK JAR."""
        return self.command([self.args.java, "-cp", self.sdk_jar, ROOT / "tools/pilot/SdkFingerprint.java", DESTINATION], body)

    def pet_headers(self, key=None, body=None):
        """Constructs transport headers privately; callers archive payloads only."""
        result = {"X-MIEC-Api-Key": self.api_key}
        if key:
            result.update({"Idempotency-Key": key, "X-MIEC-Destination": DESTINATION, "X-MIEC-Fingerprint": self.fingerprint(body), "X-MIEC-Fencing-Token": "1"})
        return result

    def prepare(self):
        """Pins sanitized artifacts, commits, diffs, SDK identity and the real application dataset."""
        artifacts = self.folder / "artifacts"
        artifacts.mkdir()
        self.miec_jar, self.petclinic_jar, self.sdk_jar = (artifacts / name for name in ("miec.jar", "petclinic.jar", "miec-recipient-sdk.jar"))
        for source, target, resource, variable in [(self.args.miec_jar, self.miec_jar, "application-postgres.properties", "MIEC_POSTGRES_PASSWORD"),
                                                   (self.args.petclinic_jar, self.petclinic_jar, "application-miec-postgres.properties", "MIEC_PETCLINIC_POSTGRES_PASSWORD")]:
            with zipfile.ZipFile(source) as archive:
                values = archive.read("BOOT-INF/classes/" + resource).decode()
                password = re.search(r"(?m)^spring.datasource.password=(.*)$", values)[1].strip()
                require(password in ("${" + variable + ":xxx}", "${" + variable + "}"), "Prepare sanitized pilot artifacts before archiving JARs")
            shutil.copyfile(source, target)
        shutil.copyfile(self.args.sdk_jar, self.sdk_jar)
        with zipfile.ZipFile(self.petclinic_jar) as archive:
            names = [name for name in archive.namelist() if re.fullmatch(r"BOOT-INF/lib/miec-recipient-sdk-.*\.jar", name)]
            require(len(names) == 1, "PetClinic must contain exactly one shared recipient SDK")
            require(hashlib.sha256(archive.read(names[0])).hexdigest() == sha256(self.sdk_jar), "Embedded SDK differs from the pinned artifact")
        self.manifest["artifacts"] = {name: {"path": str(path.relative_to(self.folder)), "sha256": sha256(path)} for name, path in [("miec", self.miec_jar), ("petclinic", self.petclinic_jar), ("sdk", self.sdk_jar)]}
        self.manifest["repositories"] = {}
        for name, repo in [("miec", ROOT), ("petclinic", ROOT.parent / "spring-petclinic"), ("sdk", ROOT.parent / "miec-recipient-sdk")]:
            commit = self.command(["git", "-C", repo, "rev-parse", "HEAD"])
            status = self.command(["git", "-C", repo, "status", "--short"])
            diff = self.command(["git", "-C", repo, "diff", "HEAD", "--", ".", ":(exclude)**/*.properties"])
            self.write(f"sources/{name}.json", {"commit": commit, "status": status, "diffExcludingPrivateProperties": diff})
            self.manifest["repositories"][name] = {"commit": commit, "dirty": bool(status), "sourceEvidence": f"sources/{name}.json"}
        harness = self.folder / "harness"
        harness.mkdir()
        for source in Path(__file__).parent.glob("*"):
            if source.suffix in (".py", ".java", ".ps1"):
                shutil.copyfile(source, harness / source.name)
        self.manifest["java"] = self.command([self.args.java, "-version"]) or "See java-version.txt"
        version = subprocess.run([str(self.args.java), "-version"], capture_output=True, text=True, errors="replace")
        (self.folder / "java-version.txt").write_text(version.stdout + version.stderr, encoding="utf-8")
        self.proxy = FaultProxy(self)
        self.proxy_base = f"http://127.0.0.1:{self.proxy.server_port}"
        self.manifest["ports"] = {"miec": self.miec_port, "petclinic": self.pet_port, "proxy": self.proxy.server_port}
        threading.Thread(target=self.proxy.serve_forever, daemon=True).start()
        self.start("petclinic")
        self.start("miec")
        self.get("/api/execution-adapters")
        marker = self.run_id[-8:]
        owner = urllib.parse.urlencode({"firstName": "MVP", "lastName": "Pilot" + marker, "address": "Local laboratory", "city": "Bogota", "telephone": "1234567890"}).encode()
        _, _, location = self.http(self.pet_base, "/owners/new", role=None, raw=owner, headers={"Content-Type": "application/x-www-form-urlencoded"}, expected=302)
        self.owner_id = int(re.search(r"/owners/(\d+)", location)[1])
        pet_name = "Pilot" + marker
        pet = urllib.parse.urlencode({"name": pet_name, "birthDate": "2020-01-01", "type": "dog"}).encode()
        self.http(self.pet_base, f"/owners/{self.owner_id}/pets/new", role=None, raw=pet, headers={"Content-Type": "application/x-www-form-urlencoded"}, expected=302)
        self.pet_id = int(self.sql(self.pet_db, f"SELECT id FROM pets WHERE owner_id={self.owner_id} AND name={sql_text(pet_name)};"))
        require(self.owner_id != 900001 and self.pet_id != 900001, "Business dataset must differ from profile fixtures")
        self.visit_date = self.sql(self.pet_db, "SELECT (CURRENT_DATE+7)::text;")
        self.manifest["dataset"] = {"ownerId": self.owner_id, "petId": self.pet_id, "date": self.visit_date, "createdThrough": "PetClinic HTML HTTP forms", "profileFixtureIdsUsedAsOwnerOrPet": False}
        self.passed("LP-01", "Dos JAR reales PostgreSQL; SDK embebido verificado; propietario y mascota nuevos por HTTP")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """Keeps HTML form redirects observable instead of silently following them."""
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        """Returns the original 302 as evidence of the actual form submission."""
        return None


class FaultProxy(ThreadingHTTPServer):
    """Forwards to real PetClinic and only drops responses after verifying its commit."""
    daemon_threads = True

    def __init__(self, pilot):
        """Binds loopback only; business storage and decisions remain in PetClinic."""
        self.pilot = pilot
        super().__init__(("127.0.0.1", 0), Forwarder)


class Forwarder(BaseHTTPRequestHandler):
    """Preserves exact request bytes and private transport headers for the real server."""
    def log_message(self, format, *args):
        """Avoids HTTP server logging of headers or transport secrets."""
        pass

    def do_GET(self):
        """Forwards the recipient's genuine lookup response without fabricating evidence."""
        self.forward()

    def do_POST(self):
        """Forwards execution and applies the explicitly armed post-commit transport fault."""
        self.forward()

    def forward(self):
        """Checks committed SQL identity before discarding any success response."""
        pilot = self.server.pilot
        require(self.path == "/miec/visits" or re.fullmatch(r"/miec/operations/[0-9a-f-]+", self.path), "Unexpected proxy route")
        data = self.rfile.read(int(self.headers.get("Content-Length", 0))) if self.command == "POST" else None
        headers = {key: value for key, value in self.headers.items() if key.lower() in ("content-type", "x-miec-api-key", "idempotency-key", "x-miec-destination", "x-miec-fingerprint", "x-miec-fencing-token")}
        request = urllib.request.Request(pilot.pet_base + self.path, data=data, headers=headers, method=self.command)
        try:
            response = urllib.request.urlopen(request, timeout=25)
        except urllib.error.HTTPError as failure:
            response = failure
        with response:
            status, content = response.code, response.read()
        if self.command == "POST" and status in (200, 201) and pilot.drop_post:
            result = json.loads(content)
            key = str(uuid.UUID(result["operationKey"]))
            saved = json.loads(pilot.sql(pilot.pet_db, f"SELECT row_to_json(o) FROM miec_visit_operations o WHERE operation_key={sql_text(key)}::uuid;"))
            require(saved["visit_id"] == result["visitId"] and saved["result_reference"] == result["resultReference"], "Cannot drop response before a verified database commit")
            with pilot.lock:
                pilot.fault_keys.add(key)
                pilot.write("faults/post-commit-" + key + ".json", {"backendStatus": status, "backendResult": result, "committedLedger": saved, "action": "connection closed before forwarding response"})
            self.close_connection = True
            try:
                self.connection.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            self.connection.close()
            return
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(content)))
        self.end_headers()
        try:
            self.wfile.write(content)
        except (BrokenPipeError, ConnectionResetError):
            pass


def prepare_round(p, number, cycle=None):
    """Creates real observations, diagnosis, executable plan and an independently named approval."""
    p.case = "LP-02" if number == 1 else "LP-09"
    marker = p.run_id + "-R" + str(number)
    body = json.dumps({"ownerId": p.owner_id, "petId": p.pet_id, "date": p.visit_date, "description": marker}, separators=(",", ":"))
    if cycle is None:
        system = p.post("/api/systems", {"name": p.run_id, "domain": "local PetClinic integration", "description": "Synthetic business acceptance, not causal software improvement"}, expected=201)
        p.system = system["systemId"]
        cycle = p.post(f"/api/systems/{p.system}/cycles", {}, expected=201)["cycleId"]
        p.post(f"/api/cycles/{cycle}/start", {"expectedVersion": p.version(cycle), "reason": "MVP08 local pilot"})
        p.post(f"/api/cycles/{cycle}/measurement/initialize", {"expectedVersion": p.version(cycle), "objective": "Registro autorizado sin visitas adicionales",
               "successCriteria": "V=1, K=1 y max(0,V-1)=0; no atribucion causal", "reason": "criterio medible de integridad",
               "criteria": [{"name": "visitas adicionales", "unit": "count", "operator": "EQ", "target": 0}]})
    root = f"/api/cycles/{cycle}"
    context = p.get(root + "/measurement")
    require(not context["baselines"], "New round must not reuse the previous baseline")
    objective = context["objectives"][0]
    indicator = objective["criteria"][0]["indicatorId"]
    before = p.observation(marker, evidence=f"measurements/round-{number}-baseline.json")
    require(before["V"] == 0 and before["K"] == 0, "Initial business count must be genuinely zero")
    p.post(root + "/measurement/baselines", {"expectedVersion": p.version(cycle), "indicatorId": indicator, "value": max(0, before["V"] - 1),
           "unit": "count", "source": f"SQL observation {p.run_id} round {number}; declared human report", "measuredAt": before["observedAt"], "reason": "V=0 observed before execution"})
    baseline = p.get(root + "/measurement")["baselines"][0]
    observed = p.post(root + "/measurement/complete", {"expectedVersion": p.version(cycle), "reason": "baseline observed and preserved"})
    p.post(root + "/diagnosis", {"expectedVersion": p.version(cycle), "evidenceId": observed["resourceId"], "summary": "Verificar autorizacion, efecto unico y recuperacion sin duplicacion", "reason": "diagnostico trazable"})
    spec = {"adapterId": "http-recipient-v1", "adapterVersion": "1", "destinationId": DESTINATION, "mediaType": "application/json", "body": body}
    plan = p.post(root + "/plans", {"expectedVersion": p.version(cycle), "objectiveId": objective["objectiveId"], "expectedOutcomes": "Una visita para la operacion, sin duplicados",
                  "rollbackStrategy": "No compensacion automatica; conservar ledger y consultar antes de reconciliar", "actions": [{"actionType": "PROCESS_EXECUTION", "description": marker, "execution": spec}]}, expected=201)
    plan_id, action_id = plan["planId"], plan["actions"][0]["actionId"]
    if number == 1:
        p.case = "LP-11"
        p.post(root + f"/plans/{plan_id}/execution", {"expectedCycleVersion": p.version(cycle), "expectedPlanVersion": plan["version"], "reason": "reject execution without approved authorization"}, expected=409)
    permission = p.post(f"/api/plans/{plan_id}/authorization-requests", {"expectedPlanVersion": plan["version"], "actionId": action_id, "validForSeconds": 1800}, expected=201)
    p.get(f"/api/plans/{plan_id}/context", "approver")
    if number == 1:
        p.post(f"/api/authorizations/{permission['authorizationId']}/approve", {"expectedVersion": permission["version"], "reason": "wrong role must fail"}, expected=403)
    p.post(f"/api/authorizations/{permission['authorizationId']}/approve", {"expectedVersion": permission["version"], "reason": "Automated approver role reviewed immutable content"}, "approver")
    plan = p.get(f"/api/plans/{plan_id}")
    if number == 1:
        p.post(f"/api/authorizations/{permission['authorizationId']}/approve", {"expectedVersion": permission["version"], "reason": "stale authorization version"}, "approver", expected=409)
        foreign = p.post(f"/api/systems/{p.system}/cycles", {}, expected=201)
        p.manifest["negativeCycleId"] = foreign["cycleId"]
        p.post(f"/api/cycles/{foreign['cycleId']}/plans/{plan_id}/execution", {"expectedCycleVersion": foreign["version"], "expectedPlanVersion": plan["version"], "reason": "foreign cycle must fail"}, expected=400)
        require(p.observation(marker)["V"] == 0, "Rejected MIEC commands must not reach the recipient")
        p.passed("LP-11", "403 rol incorrecto; 409 version obsoleta y ejecucion sin permiso; 400 ciclo ajeno; V=0")
    result = {"number": number, "cycleId": cycle, "planId": plan_id, "actionId": action_id, "authorizationId": permission["authorizationId"],
              "objectiveId": objective["objectiveId"], "indicatorId": indicator, "baselineId": baseline["baselineId"], "baseline": baseline, "marker": marker, "body": body}
    p.rounds.append(result)
    p.flush()
    return result, plan


def execute_round(p, record, plan, concurrent=False):
    """Executes through MIEC IR-009; optional competing callers must yield one reservation."""
    path = f"/api/cycles/{record['cycleId']}/plans/{record['planId']}/execution"
    command = {"expectedCycleVersion": p.version(record["cycleId"]), "expectedPlanVersion": plan["version"], "reason": "execute approved pilot operation"}
    if concurrent:
        barrier = threading.Barrier(2)
        def submit():
            """Starts the identical versioned MIEC command at the shared barrier."""
            barrier.wait(timeout=10)
            return p.http(p.base, path, command)
        with concurrent_futures_pool(2) as pool:
            replies = list(pool.map(lambda _: submit(), range(2)))
        require(sorted(item[0] for item in replies) == [201, 409], "Exactly one MIEC concurrent start must reserve the plan")
        run = next(item[1] for item in replies if item[0] == 201)
    else:
        run = p.post(path, command, expected=201)
    step = run["steps"][0]
    record.update(runId=run["runId"], operationKey=step["operationKey"], executionId=step["executionId"])
    p.flush()
    return run


def concurrent_futures_pool(size):
    """Keeps the executor name independent of the execution mode argument."""
    return concurrent.futures.ThreadPoolExecutor(max_workers=size)


def finish_execution(p, record, run):
    """Allows only the approver to confirm E08 once the effective outcome is applied."""
    result = p.post(f"/api/plans/{record['planId']}/execution/finish", {"expectedVersion": run["version"], "expectedCycleVersion": p.version(record["cycleId"]), "reason": "confirm independently observed recipient result"}, "approver")
    require(result["cycleState"] == "E08_VERIFICANDO", "Finalization must enter E08")
    counts = p.observation(record["marker"], record["operationKey"], f"measurements/round-{record['number']}-effect.json")
    require(counts["V"] == 1 and counts["K"] == 1, "One visit and one ledger row are required in addition to a zero duplicate indicator")
    return result


def measure_and_conclude(p, record, action):
    """Persists real counts and conservative evaluation without upgrading human evidence to EV-2."""
    cycle, plan = record["cycleId"], record["planId"]
    root = f"/api/cycles/{cycle}"
    path = root + f"/plans/{plan}/measurements"
    observation = p.observation(record["marker"], record["operationKey"], f"measurements/round-{record['number']}-post.json")
    require(observation["V"] == 1 and observation["K"] == 1, "Measurement must be backed by the raw successful effect counts")
    posts = p.post(path, {"runId": record["runId"], "method": METHOD, "measurement": {"expectedVersion": p.version(cycle), "indicatorId": record["indicatorId"],
                   "value": max(0, observation["V"] - 1), "unit": "count", "source": "SQL count declared as HUMAN_REPORT; see pilot dossier", "measuredAt": observation["observedAt"], "reason": "post-execution count actually observed"}})
    measured = posts["measurements"][0]
    for proof in (record["baseline"]["evidenceId"], measured["evidenceId"]):
        posts = p.post(path + "/reviews", {"expectedVersion": posts["cycleVersion"], "runId": record["runId"], "evidenceId": proof, "verdict": "ACCEPTED_WITH_LIMITATIONS",
                       "method": METHOD, "procedureReference": p.run_id, "limitations": "Automated local roles, human-report policy, no instrumental authentication or causal attribution", "reason": "review with explicit limits"}, "approver")
    sealed = p.post(path + "/seal", {"expectedVersion": posts["cycleVersion"], "runId": record["runId"], "method": METHOD,
                    "baselineFrom": record["baseline"]["measuredAt"], "baselineTo": record["baseline"]["measuredAt"], "postFrom": measured["measuredAt"], "postTo": measured["measuredAt"],
                    "pairs": [{"indicatorId": record["indicatorId"], "baselineId": record["baselineId"], "measurementId": measured["measurementId"]}], "reason": "explicit selected point observations"}, "approver")
    admitted = p.post(root + f"/plans/{plan}/evaluations", {"expectedVersion": sealed["cycleVersion"], "setId": sealed["set"]["setId"], "reason": "deterministic comparison; not causal improvement"}, expected=201)
    evaluation = admitted["evaluation"]["report"]["result"]
    require(evaluation["classification"] == "INCONCLUSIVE", "Human reports must remain RS-05 under SOURCE-QUALITY-V1")
    decision = p.post(root + "/decisions", {"expectedVersion": admitted["completion"]["cycle"]["version"], "evaluationId": evaluation["id"], "action": action,
                      "acknowledgeLimitations": True, "reason": "Administrative decision without claiming instrumental sufficiency"}, "approver")
    learned = p.post(root + "/learning", {"expectedVersion": decision["cycle"]["version"], "decisionId": decision["decision"]["decisionId"],
                     "statement": "Integridad V=1/K=1 y criterio 0 conservados; no mejora concluyente acreditada", "conditions": "Local synthetic business pilot over real PostgreSQL and packaged applications",
                     "limitations": "One automated operator simulates AUTHOR/APPROVER; no causal or EV-2/EV-3 claim", "reason": "record bounded learning"})
    rounds = p.get(root + "/rounds")
    if not rounds["rounds"]:
        rounds = p.post(root + "/rounds/initial", {"expectedCycleVersion": learned["cycle"]["version"], "reason": "freeze initial round context"})
    current = rounds["rounds"][-1]
    concluded = p.post(root + f"/rounds/{current['roundId']}/plan-completion", {"expectedCycleVersion": rounds["cycle"]["version"], "expectedRoundVersion": current["version"], "reason": "confirm operational plan conclusion"}, "approver")
    snapshot = concluded["rounds"][-1]["snapshot"]
    record.update(roundId=current["roundId"], measurementId=measured["measurementId"], postEvidenceId=measured["evidenceId"], setId=sealed["set"]["setId"],
                  evaluationId=evaluation["id"], decisionId=decision["decision"]["decisionId"], learningId=learned["learning"]["learningId"], classification=evaluation["classification"])
    p.write(f"contexts/round-{record['number']}-snapshot.json", snapshot)
    p.flush()
    return concluded


def contract_probes(p, record):
    """Exercises read, replay, concurrency and invalid transport against real PetClinic."""
    key, body, marker = record["operationKey"], record["body"], record["marker"]
    p.case = "LP-03"
    original = p.http(p.pet_base, "/miec/operations/" + key, role=None, headers=p.pet_headers(), expected=200)[1]
    for _ in range(3):
        found = p.http(p.pet_base, "/miec/operations/" + key, role=None, headers=p.pet_headers(), expected=200)[1]
        require(found == original, "Lookup must return the unchanged durable receipt")
        p.get(f"/api/plans/{record['planId']}/execution")
    require(p.observation(marker, key)["V"] == 1, "Queries must not create visits")
    p.passed("LP-03", "Consultas repetidas conservaron resultado y V=1/K=1")
    p.case = "LP-04"
    headers = p.pet_headers(key, body)
    require(headers["X-MIEC-Fingerprint"] == original["fingerprint"], "SDK and MIEC must agree over the approved exact UTF-8 body")
    barrier = threading.Barrier(8)
    def replay():
        """Repeats the original operation at the concurrent barrier, never creates a new key."""
        barrier.wait(timeout=10)
        return p.http(p.pet_base, "/miec/visits", role=None, raw=body.encode(), headers=headers, expected=200)[1]
    with concurrent_futures_pool(8) as pool:
        replies = list(pool.map(lambda _: replay(), range(8)))
    require(all(item == original for item in replies), "All concurrent replays must agree exactly")
    after = p.observation(marker, key, "measurements/concurrent-replay.json")
    require(after["V"] == 1 and after["K"] == 1, "Concurrent replays must leave one durable effect")
    p.passed("LP-04", "Dos starts MIEC: 201/409; ocho replays concurrentes: 200, mismo resultado y V=1/K=1")
    p.case = "LP-05"
    changed = json.loads(body)
    changed["description"] += "-conflict"
    changed_body = json.dumps(changed, separators=(",", ":"))
    p.http(p.pet_base, "/miec/visits", role=None, raw=changed_body.encode(), headers=p.pet_headers(key, changed_body), expected=409)
    require(p.http(p.pet_base, "/miec/operations/" + key, role=None, headers=p.pet_headers(), expected=200)[1] == original, "Conflict must not overwrite the receipt")
    require(p.observation(changed["description"], key)["V"] == 0, "Conflicting content must not create an unlinked visit")
    p.passed("LP-05", "Otra huella valida con la misma clave recibio 409; visita y resultado originales intactos")
    p.case = "LP-06"
    invalid = json.loads(body)
    invalid["ownerId"], invalid["description"] = 900001, p.run_id + "-invalid-owner"
    invalid_body, invalid_key = json.dumps(invalid, separators=(",", ":")), str(uuid.uuid4())
    p.http(p.pet_base, "/miec/visits", role=None, raw=invalid_body.encode(), headers=p.pet_headers(invalid_key, invalid_body), expected=400)
    counts = p.observation(invalid["description"], invalid_key, "measurements/invalid-owner.json")
    require(counts["V"] == 0 and counts["K"] == 0, "Invalid ownership must roll back reservation and effect")
    wrong_key = str(uuid.uuid4())
    wrong_headers = p.pet_headers(wrong_key, body)
    wrong_headers["X-MIEC-Fingerprint"] = "0" * 64
    p.http(p.pet_base, "/miec/visits", role=None, raw=body.encode(), headers=wrong_headers, expected=400)
    malformed_key = str(uuid.uuid4())
    malformed = dict(wrong_headers, **{"Idempotency-Key": malformed_key})
    p.http(p.pet_base, "/miec/visits", role=None, raw=b"\xc3(", headers=malformed, expected=400)
    p.http(p.pet_base, "/miec/operations/" + wrong_key, role=None, headers={"X-MIEC-Api-Key": "invalid-pilot-credential"}, expected=401)
    for rejected_key in (wrong_key, malformed_key):
        require(p.observation(marker, rejected_key)["K"] == 0, "Rejected transport must not reserve a durable key")
    require(p.observation(marker, key)["V"] == 1, "Negative probes must preserve the original effect count")
    p.passed("LP-06", "Propietario ajeno, huella incorrecta, UTF-8 invalido y credencial invalida rechazados sin efectos nuevos")


def reconcile_after_loss(p, record, run):
    """Preserves UNKNOWN across independent restarts, then obtains authorized real destination evidence."""
    p.case = "LP-07"
    key, plan, execution = record["operationKey"], record["planId"], record["executionId"]
    require(key in p.fault_keys, "The fault must follow a verified PetClinic commit")
    require(p.observation(record["marker"], key)["V"] == 1, "Remote visit must be committed before recovery")
    state = p.sql(p.miec_db, f"SELECT execution_status FROM execution_attempt WHERE execution_id={sql_text(execution)}::uuid;", "contexts/unknown-before-restart.json")
    require(state == "UNKNOWN", "A lost success response must not be converted to local success")
    p.post(f"/api/plans/{plan}/execution/finish", {"expectedVersion": run["version"], "expectedCycleVersion": p.version(record["cycleId"]), "reason": "unconfirmed finish must fail"}, "approver", expected=409)
    receipt = p.http(p.pet_base, "/miec/operations/" + key, role=None, headers=p.pet_headers(), expected=200)[1]
    p.case = "LP-08"
    p.stop("petclinic")
    p.start("petclinic")
    require(p.http(p.pet_base, "/miec/operations/" + key, role=None, headers=p.pet_headers(), expected=200)[1] == receipt, "PetClinic restart must preserve the exact receipt")
    p.stop("miec")
    p.start("miec")
    run = p.get(f"/api/plans/{plan}/execution")
    require(p.sql(p.miec_db, f"SELECT execution_status FROM execution_attempt WHERE execution_id={sql_text(execution)}::uuid;") == "UNKNOWN", "MIEC restart must preserve the unresolved technical outcome")
    require(run["steps"][0]["operationKey"] == key, "Restart must never replace the stable operation key")
    p.passed("LP-08", "Ambos JAR reiniciados por separado; PetClinic conserva recibo y MIEC conserva UNKNOWN y la clave")
    p.case = "LP-07"
    path = f"/api/plans/{plan}/execution"
    p.post(path + "/resume", {"expectedVersion": run["version"], "reason": "no blind replay"}, expected=409)
    recovered = p.post(path + "/recover", {"expectedVersion": run["version"], "reason": "recover uncertain projection without sending effect"}, "approver")
    require(recovered["state"] == "RECOVERY_REQUIRED", "Missing confirmation must remain blocked")
    permission = p.post(path + "/reconciliation-requests", {"expectedVersion": recovered["version"], "actionId": record["actionId"], "validForSeconds": 600, "reason": "authorize destination lookup"}, expected=201)
    p.post(f"/api/execution/reconciliation-authorizations/{permission['authorizationId']}/approve", {"expectedVersion": permission["version"], "reason": "approver grants bounded reconciliation"}, "approver")
    current = p.get(path)
    confirmed = p.post(path + "/reconcile", {"expectedVersion": current["version"], "actionId": record["actionId"], "authorizationId": permission["authorizationId"], "reason": "query authenticated PetClinic result"}, "approver")
    require(confirmed["state"] == "APPLIED", "Authorized destination evidence must resolve the effective outcome")
    decision = json.loads(p.sql(p.miec_db, f"SELECT row_to_json(r) FROM execution_reconciliation r WHERE execution_id={sql_text(execution)}::uuid;", "contexts/reconciliation.json"))
    require(decision["original_status"] == "UNKNOWN" and decision["resolution"] == "APPLIED", "Reconciliation must retain the original uncertainty")
    record.update(reconciliationAuthorizationId=permission["authorizationId"], reconciliationId=decision["decision_id"], reconciliationEvidenceId=decision["evidence_id"])
    finish_execution(p, record, confirmed)
    p.passed("LP-07", "Corte posterior a commit produjo UNKNOWN; permiso independiente y consulta autenticada resolvieron APPLIED, V=1/K=1")


def checkpoint(p):
    """Captures immutable public resources for comparisons after restoring both databases."""
    cycle = p.rounds[0]["cycleId"]
    paths = [f"/api/cycles/{cycle}", f"/api/cycles/{cycle}/rounds", f"/api/cycles/{cycle}/completion", f"/api/cycles/{cycle}/history"]
    for record in p.rounds:
        paths += [f"/api/plans/{record['planId']}/execution", f"/api/evaluations/{record['evaluationId']}", f"/api/cycles/{cycle}/plans/{record['planId']}/measurements"]
    return {path: p.get(path) for path in paths}


def restore_pair(p):
    """Stops both writers and restores complete dumps into two fresh, owned databases."""
    p.case = "LP-12"
    before = checkpoint(p)
    p.write("checkpoint/resources.json", before)
    receipts = {r["operationKey"]: p.http(p.pet_base, "/miec/operations/" + r["operationKey"], role=None, headers=p.pet_headers(), expected=200)[1] for r in p.rounds}
    p.stop("miec")
    p.stop("petclinic")
    backups = p.folder / "backups"
    backups.mkdir()
    restored = {}
    suffix = uuid.uuid4().hex[:16]
    for label, database in (("miec", p.miec_db), ("petclinic", p.pet_db)):
        remote = "/tmp/" + p.run_id + "-" + label + ".dump"
        archive = backups / (label + ".dump")
        p.command(["docker", "exec", p.args.postgres_container, "pg_dump", "-U", "postgres", "-Fc", "-d", database, "-f", remote], timeout=90)
        p.command(["docker", "cp", p.args.postgres_container + ":" + remote, archive])
        clone = "mvp08_restore_" + label + "_" + suffix
        require(re.fullmatch(r"mvp08_restore_(miec|petclinic)_[0-9a-f]{16}", clone), "Invalid owned restore target")
        p.sql("postgres", 'CREATE DATABASE "' + clone + '";')
        p.command(["docker", "exec", p.args.postgres_container, "pg_restore", "-U", "postgres", "--exit-on-error", "--no-owner", "--no-privileges", "-d", clone, remote], timeout=90)
        restored[label] = clone
        p.manifest.setdefault("backups", {})[label] = {"database": database, "file": str(archive.relative_to(p.folder)), "sha256": sha256(archive)}
    p.manifest["restoredDatabases"] = restored
    p.miec_db, p.pet_db = restored["miec"], restored["petclinic"]
    p.flush()
    p.start("petclinic", restored=True)
    p.start("miec", restored=True)
    after = checkpoint(p)
    require(after == before, "Restored MIEC resources must equal the complete checkpoint")
    for record in p.rounds:
        key = record["operationKey"]
        found = p.http(p.pet_base, "/miec/operations/" + key, role=None, headers=p.pet_headers(), expected=200)[1]
        require(found == receipts[key], "Restored PetClinic must preserve the original receipt")
        replay = p.http(p.pet_base, "/miec/visits", role=None, raw=record["body"].encode(), headers=p.pet_headers(key, record["body"]), expected=200)[1]
        require(replay == found, "Replay after restore must not create another effect")
        counts = p.observation(record["marker"], key, f"checkpoint/restored-round-{record['number']}.json")
        require(counts["V"] == 1 and counts["K"] == 1, "Restored counts must remain one per deliberate operation")
    p.write("checkpoint/restored-resources.json", after)
    p.passed("LP-12", "Ambas JVM detenidas; dumps SHA-256 restaurados en dos bases nuevas; recursos identicos y replay sin efectos adicionales")


def execute_pilot(p):
    """Runs LP-01 through LP-12 as one coherent two-round acceptance, never skipping blocked outcomes."""
    p.prepare()
    first, plan = prepare_round(p, 1)
    p.case = "LP-02"
    run = execute_round(p, first, plan, concurrent=True)
    require(run["state"] == "APPLIED", "The first real operation must be confirmed")
    finish_execution(p, first, run)
    p.passed("LP-02", f"Plan autorizado {first['planId']}, E08 y una visita para la clave {first['operationKey']}")
    contract_probes(p, first)
    p.case = "LP-09"
    concluded = measure_and_conclude(p, first, "COLLECT_EVIDENCE")
    old_snapshot = concluded["rounds"][0]["snapshot"]
    current = concluded["rounds"][0]
    root = f"/api/cycles/{first['cycleId']}"
    request = p.post(root + f"/rounds/{current['roundId']}/continuations", {"expectedCycleVersion": concluded["cycle"]["version"], "expectedRoundVersion": current["version"],
                     "decisionId": old_snapshot["decision"]["decisionId"], "learningId": old_snapshot["learning"]["learningId"], "mode": "COLLECT_EVIDENCE", "acknowledgeLimitations": True,
                     "reason": "New round with its own baseline, plan and authorization", "baselines": []})
    p.manifest["continuationRequestId"] = request["requestId"]
    p.stop("miec")
    p.start("miec")
    pending = p.get(root + "/rounds/continuations/" + request["requestId"])
    require(pending == request and pending["status"] == "PENDING", "Pending continuation must survive a real MIEC restart")
    continued = p.post(root + "/rounds/continuations/" + request["requestId"] + "/approve", {"expectedCycleVersion": pending["cycleVersion"], "expectedRoundVersion": pending["roundVersion"],
                       "expectedRequestVersion": pending["version"], "acknowledgeLimitations": True, "reason": "Approver role accepts bounded continuation"}, "approver")
    require(continued["cycle"]["state"] == "E02_OBSERVANDO", "Continuation must use F01 E11 to E02")
    require(continued["rounds"][0]["snapshot"] == old_snapshot, "Continuing must preserve the first immutable snapshot")
    second, plan = prepare_round(p, 2, first["cycleId"])
    p.case = "LP-07"
    p.drop_post = True
    try:
        run = execute_round(p, second, plan)
    finally:
        p.drop_post = False
    reconcile_after_loss(p, second, run)
    for field in ("planId", "actionId", "authorizationId", "baselineId", "operationKey"):
        require(first[field] != second[field], "Round two must not reuse " + field)
    p.passed("LP-09", "Continuidad pendiente sobrevivio reinicio; E11 a E02 y muestras, plan, acciones, permiso y clave nuevos")
    p.case = "LP-10"
    concluded = measure_and_conclude(p, second, "CLOSE_CYCLE")
    require(concluded["rounds"][0]["snapshot"] == old_snapshot, "Second evaluation must preserve round-one context")
    completion = p.get(root + "/completion")
    closed = p.post(root + "/close", {"expectedVersion": concluded["cycle"]["version"], "learningId": completion["learning"]["learningId"], "summary": "Integridad tecnica aceptada con evidencia humana inconclusa",
                    "acknowledgeLimitations": True, "reason": "Close two deliberate operations without causal success claim"}, "approver")
    require(closed["cycle"]["state"] == "E14_FINALIZADO", "The full pilot must reach E14")
    require(len(p.get(root + "/rounds")["rounds"]) == 2, "Exactly two rounds must belong to the main cycle")
    for record in p.rounds:
        require(record["classification"] == "INCONCLUSIVE", "Do not infer EV-2 or success from the local counts")
        require(p.observation(record["marker"], record["operationKey"])["V"] == 1, "One deliberate visit per round")
    p.passed("LP-10", "Dos rondas completas, snapshots conservados y E14; evaluaciones RS-05, sin incertidumbre efectiva pendiente")
    restore_pair(p)


def main():
    """Parses the opt-in operator command and always closes only pilot-owned servers."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", action="store_true", help="Required: creates real synthetic business records in the configured local databases")
    parser.add_argument("--miec-jar", type=Path, default=ROOT / "target/miec-prototype-0.0.1-SNAPSHOT.jar")
    parser.add_argument("--petclinic-jar", type=Path, default=ROOT.parent / "spring-petclinic/target/spring-petclinic-4.0.0-SNAPSHOT.jar")
    parser.add_argument("--sdk-jar", type=Path, required=True)
    parser.add_argument("--java", type=Path, default=Path(shutil.which("java") or "java"))
    parser.add_argument("--evidence-root", type=Path, default=Path(os.environ.get("LOCALAPPDATA", str(Path.home()))) / "MIEC/pilots")
    parser.add_argument("--postgres-container", default="postgres14")
    args = parser.parse_args()
    require(args.run, "Pass --run only for the explicitly authorized local acceptance")
    args.miec_jar, args.petclinic_jar, args.sdk_jar = (path.resolve(strict=True) for path in (args.miec_jar, args.petclinic_jar, args.sdk_jar))
    pilot = Pilot(args)
    print("EVIDENCE=" + str(pilot.folder), flush=True)
    status = 1
    try:
        execute_pilot(pilot)
        require(all(case["status"] == "APROBADO" for case in pilot.cases.values()), "All twelve cases must be evidenced")
        pilot.manifest["technicalAcceptance"] = "APROBADO"
        status = 0
    except Exception as failure:
        pilot.cases[pilot.case].update(status="FALLIDO", result=str(failure))
        pilot.manifest["technicalAcceptance"] = "FALLIDO"
        print(f"PILOT FAILED {pilot.case}: {failure}", file=sys.stderr, flush=True)
        pilot.write("failure.json", {"case": pilot.case, "type": type(failure).__name__, "message": str(failure)})
    finally:
        for kind in list(pilot.processes):
            try:
                pilot.stop(kind)
            except Exception:
                process = pilot.processes[kind]
                process.kill()
                process.wait(timeout=10)
                pilot.manifest.setdefault("cleanupWarnings", []).append("Forced cleanup of owned " + kind + " process")
        if hasattr(pilot, "proxy"):
            pilot.proxy.shutdown()
            pilot.proxy.server_close()
        pilot.flush()
        lines = ["# Acta tecnica MVP-08", "", "RUNID: " + pilot.run_id, "", "Resultado: " + pilot.manifest["technicalAcceptance"], "",
                 "AUTHOR y APPROVER fueron roles automatizados de una sola operacion de laboratorio, no dos personas independientes.",
                 "Las mediciones permanecen HUMAN_REPORT/UNVERIFIED y RS-05; no se acredita causalidad ni mejora del codigo.",
                 "La firma humana y la entrega formal versionada permanecen separadas de esta aceptacion tecnica.", "", "| Caso | Estado | Resultado |", "|---|---|---|"]
        lines += ["| " + item["id"] + " | " + item["status"] + " | " + item["result"].replace("|", "/").replace("\n", " ") + " |" for item in pilot.cases.values()]
        (pilot.folder / "acceptance.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("MVP08 RESULT=" + pilot.manifest["technicalAcceptance"] + "; EVIDENCE=" + str(pilot.folder), flush=True)
    return status


if __name__ == "__main__":
    sys.exit(main())
