"""Pruebas sin red del diagnóstico y de la protección de credenciales de CI."""

import base64
import contextlib
import io
import unittest
import urllib.error
from email.message import Message
from unittest.mock import MagicMock

import check_sdk_access as diagnostic


class SdkAccessTest(unittest.TestCase):
    """Verifica cada frontera de autorización sin utilizar secretos reales."""

    def setUp(self):
        """Prepara una credencial ficticia y un transporte completamente simulado."""
        self.env = dict(SDK_READ_TOKEN="fake-test-credential", SDK_REPOSITORY="owner/sdk", SDK_REF="a" * 40)
        self.opener = MagicMock()
        response = MagicMock()
        response.status = 200
        response.headers = Message()
        response.headers["Content-Type"] = "application/x-git-upload-pack-advertisement"
        self.opener.open.return_value.__enter__.return_value = response

    def test_missing_secret_stops_before_network(self):
        """Un secreto ausente falla explícitamente sin usar otra credencial."""
        self.env.pop("SDK_READ_TOKEN")
        with self.assertRaises(diagnostic.AccessFailure) as caught:
            diagnostic.check(self.env, self.opener)
        self.assertEqual("SDK_SECRET_MISSING", caught.exception.code)
        self.opener.open.assert_not_called()

    def test_invalid_secret_is_not_echoed(self):
        """Un valor mal formado nunca se incluye en el error público."""
        self.env["SDK_READ_TOKEN"] += " "
        with self.assertRaises(diagnostic.AccessFailure) as caught:
            diagnostic.check(self.env, self.opener)
        self.assertEqual("SDK_SECRET_FORMAT_INVALID", caught.exception.code)
        self.assertNotIn(self.env["SDK_READ_TOKEN"], str(caught.exception))

    def test_success_checks_api_commit_and_git_without_logging_credentials(self):
        """La validación consulta las tres rutas sin imprimir credenciales ni su codificación."""
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            diagnostic.check(self.env, self.opener)
        requests = [call.args[0] for call in self.opener.open.call_args_list]
        encoded = base64.b64encode(b"x-access-token:fake-test-credential").decode()
        self.assertEqual(3, len(requests))
        self.assertEqual("Bearer fake-test-credential", requests[0].get_header("Authorization"))
        self.assertTrue(requests[1].full_url.endswith("/git/commits/" + "a" * 40))
        self.assertEqual("Basic " + encoded, requests[2].get_header("Authorization"))
        self.assertIn("SDK_GIT_READ_OK", output.getvalue())
        self.assertNotIn(self.env["SDK_READ_TOKEN"], output.getvalue())
        self.assertNotIn(encoded, output.getvalue())

    def test_http_errors_identify_stage_without_remote_details(self):
        """Distingue rechazo de metadata, commit y Git sin filtrar el mensaje remoto."""
        for stage, status, expected in [
            ("API_METADATA", 403, "SDK_ACCESS_FORBIDDEN"),
            ("API_METADATA", 401, "SDK_TOKEN_REJECTED"),
            ("API_METADATA", 404, "SDK_REPOSITORY_NOT_ACCESSIBLE"),
            ("API_COMMIT", 404, "SDK_PIN_NOT_ACCESSIBLE"),
            ("GIT_READ", 403, "SDK_ACCESS_FORBIDDEN"),
            ("GIT_READ", 302, "SDK_REDIRECT_REJECTED"),
        ]:
            with self.subTest(stage=stage, status=status):
                self.opener.open.side_effect = urllib.error.HTTPError("https://github.com", status, "fake-test-credential", {}, None)
                with self.assertRaises(diagnostic.AccessFailure) as caught:
                    diagnostic.request_access(self.opener, "https://github.com", "Bearer dummy", stage)
                self.assertEqual(expected, caught.exception.code)
                self.assertIn(stage, str(caught.exception))
                self.assertNotIn("fake-test-credential", str(caught.exception))

    def test_network_failure_hides_remote_exception(self):
        """Los errores del transporte no exponen URLs o credenciales de su excepción."""
        self.opener.open.side_effect = urllib.error.URLError("fake-test-credential")
        with self.assertRaises(diagnostic.AccessFailure) as caught:
            diagnostic.request_access(self.opener, "https://github.com", "Bearer dummy", "GIT_READ")
        self.assertEqual("SDK_NETWORK_ERROR", caught.exception.code)
        self.assertNotIn("fake-test-credential", str(caught.exception))

    def test_redirect_never_forwards_authorization(self):
        """Una redirección no genera otra solicitud con Authorization."""
        self.assertIsNone(diagnostic.NoRedirect().redirect_request(None, None, 302, "", {}, "https://other.invalid"))


if __name__ == "__main__":
    unittest.main()
