"""Comprueba la credencial que recibió CI, sin imprimirla ni almacenarla en Git.

Valida metadata, lectura del commit fijado y transporte git-upload-pack por
separado. No descarga código ni ejecuta contenido del repositorio destinatario.
Una autorización válida de la conexión local no sustituye esta prueba del runner.
"""

import base64
import os
import re
import sys
import urllib.error
import urllib.request


class AccessFailure(Exception):
    """Error público con un código y texto fijos, nunca con una respuesta remota."""

    def __init__(self, code, message):
        super().__init__(f"{code}: {message}")
        self.code = code


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """Impide reenviar Authorization si GitHub responde con una redirección."""

    def redirect_request(self, request, fp, code, msg, headers, new_url):
        """Una referencia trasladada debe corregirse explícitamente, sin seguirla con el token."""
        return None


def settings(environment):
    """Lee exclusivamente el secreto de SDK y referencias no secretas del workflow."""
    token = environment.get("SDK_READ_TOKEN", "")
    if not token:
        raise AccessFailure("SDK_SECRET_MISSING", "Actions no recibió RECIPIENT_SDK_READ_TOKEN. Revisar nombre, Repository secrets y alcance del evento.")
    if token != token.strip() or any(ord(char) < 33 or ord(char) > 126 for char in token):
        raise AccessFailure("SDK_SECRET_FORMAT_INVALID", "El valor recibido contiene espacios o caracteres incompatibles con un token GitHub.")
    repository = environment.get("SDK_REPOSITORY", "")
    ref = environment.get("SDK_REF", "")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository) or any(part in (".", "..") for part in repository.split("/")):
        raise AccessFailure("SDK_CONFIG_INVALID", "La referencia de repositorio debe ser owner/repository.")
    if not re.fullmatch(r"[0-9a-fA-F]{40}", ref):
        raise AccessFailure("SDK_CONFIG_INVALID", "SDK_REF debe fijar un commit SHA completo.")
    return token, repository, ref


def request_access(opener, url, authorization, stage, expected_type=None):
    """Comprueba acceso con un plazo acotado y errores saneados; no lee ni registra cuerpos."""
    request = urllib.request.Request(url, headers={
        "Authorization": authorization,
        "User-Agent": "miec-sdk-ci-access-check",
        "Accept": "application/vnd.github+json" if expected_type is None else expected_type,
    })
    try:
        with opener.open(request, timeout=20) as response:
            if response.status != 200:
                raise AccessFailure("SDK_UNEXPECTED_RESPONSE", f"{stage}: respuesta HTTP inesperada.")
            if expected_type and response.headers.get_content_type() != expected_type:
                raise AccessFailure("SDK_GIT_PROTOCOL_INVALID", "GIT_READ: la respuesta no corresponde al protocolo de lectura Git.")
    except urllib.error.HTTPError as error:
        if error.code == 401:
            raise AccessFailure("SDK_TOKEN_REJECTED", f"{stage}: GitHub rechazó la credencial recibida por Actions (HTTP 401).") from None
        if error.code == 403:
            if error.headers and error.headers.get("X-RateLimit-Remaining") == "0":
                raise AccessFailure("SDK_RATE_LIMIT", f"{stage}: límite de consultas de GitHub agotado.") from None
            raise AccessFailure("SDK_ACCESS_FORBIDDEN", f"{stage}: GitHub negó el acceso de esta credencial (HTTP 403). No implica que se necesite permiso de escritura.") from None
        if error.code == 404:
            code = "SDK_REPOSITORY_NOT_ACCESSIBLE" if stage == "API_METADATA" else "SDK_PIN_NOT_ACCESSIBLE"
            raise AccessFailure(code, f"{stage}: repositorio o commit no visible para esta credencial (HTTP 404).") from None
        if error.code in (301, 302, 303, 307, 308):
            raise AccessFailure("SDK_REDIRECT_REJECTED", f"{stage}: actualizar la referencia del repositorio; no se reenvió la credencial.") from None
        raise AccessFailure("SDK_SERVICE_ERROR", f"{stage}: GitHub respondió HTTP {error.code}.") from None
    except (urllib.error.URLError, TimeoutError, OSError):
        raise AccessFailure("SDK_NETWORK_ERROR", f"{stage}: no se confirmó acceso por un fallo de red o timeout.") from None


def check(environment, opener=None):
    """Acredita lectura de código, además de metadata, con el token exacto del secreto de CI."""
    token, repository, ref = settings(environment)
    opener = opener or urllib.request.build_opener(NoRedirect())
    print("SDK_SECRET_PRESENT: Actions recibió el secreto configurado; su valor no se muestra.")
    request_access(opener, f"https://api.github.com/repos/{repository}", f"Bearer {token}", "API_METADATA")
    print("SDK_REPOSITORY_ACCESS_OK")
    request_access(opener, f"https://api.github.com/repos/{repository}/git/commits/{ref}", f"Bearer {token}", "API_COMMIT")
    print("SDK_PIN_READ_OK")
    basic = base64.b64encode(f"x-access-token:{token}".encode("ascii")).decode("ascii")
    request_access(opener, f"https://github.com/{repository}.git/info/refs?service=git-upload-pack",
                   f"Basic {basic}", "GIT_READ", "application/x-git-upload-pack-advertisement")
    print("SDK_GIT_READ_OK: la credencial permite descargar el SDK; continúa actions/checkout.")


def main():
    """Solo escribe resultados saneados; no convierte fallos de autenticación en omisión de pruebas."""
    try:
        check(os.environ)
    except AccessFailure as failure:
        print(f"::error::{failure}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
