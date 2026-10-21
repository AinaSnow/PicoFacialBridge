"""Check the built bridge APK without a device or third-party Python packages.

Set APK_PATH to inspect a different variant; the default is the debug-signed APK.
"""

import os
from pathlib import Path
import unittest
import zipfile


ROOT = Path(__file__).resolve().parents[1]
APK = Path(os.environ.get(
    "APK_PATH", ROOT / "bridge/build/outputs/apk/debug/bridge-debug-unsigned.apk"
))


class ApkDistributionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.apk = zipfile.ZipFile(APK)
        cls.addClassCleanup(cls.apk.close)

    def test_project_mit_license_is_bundled(self):
        self.assertIn("assets/LICENSE", self.apk.namelist())
        expected = (ROOT / "LICENSE").read_text(encoding="utf-8")
        self.assertIn("MIT License", expected)
        self.assertEqual(self.apk.read("assets/LICENSE").decode("utf-8").splitlines(), expected.splitlines())

    def test_upstream_attribution_is_bundled(self):
        self.assertIn("assets/THIRD_PARTY_NOTICES.md", self.apk.namelist())
        expected = (ROOT / "THIRD_PARTY_NOTICES.md").read_text(encoding="utf-8")
        self.assertIn("Copyright (c) 2026 thoricelli", expected)
        self.assertIn("Copyright (c) 2026 UnifiedPicoModule contributors", expected)
        self.assertEqual(
            self.apk.read("assets/THIRD_PARTY_NOTICES.md").decode("utf-8").splitlines(), expected.splitlines()
        )

    def test_direct_osc_app_excludes_legacy_transport(self):
        dex = b"".join(self.apk.read(name) for name in self.apk.namelist() if name.endswith(".dex"))
        self.assertIn(b"Ldev/pico/facialprobe/DirectFaceMapper;", dex)
        self.assertIn(b"Ldev/pico/facialprobe/OscFacePacket;", dex)
        for old in ("PeerProtocol", "FramePump", "DeliveryLedger", "XDeliveryStats", "TrackingData"):
            self.assertNotIn(f"Ldev/pico/facialprobe/{old};".encode("ascii"), dex)
        self.assertIn("AndroidManifest.xml", self.apk.namelist())

    def test_only_our_arm64_native_library_is_bundled(self):
        libraries = sorted(name for name in self.apk.namelist() if name.endswith(".so"))
        self.assertEqual(libraries, ["lib/arm64-v8a/libpico_probe.so"])

    def test_no_keys_or_private_evidence_are_bundled(self):
        for name in self.apk.namelist():
            with self.subTest(name=name):
                self.assertFalse(name.endswith((".keystore", ".jks", ".pem", ".p12")))
                self.assertFalse(name.startswith(("assets/evidence/", "assets/reference/")))


class WorkflowDistributionTest(unittest.TestCase):
    def test_no_automatic_signing_fallback(self):
        config = (ROOT / "bridge/build.gradle").read_text()
        self.assertIn("debug { signingConfig null }", config)
        self.assertIn("release { signingConfig null }", config)
        self.assertIn("applicationId 'dev.pico.facialbridge.osc.direct'", config)
        script = (ROOT / "tools/sign-local.ps1").read_text()
        self.assertNotIn("-genkeypair", script)
        self.assertIn("Final APK signer mismatch", script)
        self.assertIn("Existing key does not match pinned certificate", script)
        pin = (ROOT / "tools/signing-certificate.sha256").read_text().strip()
        self.assertRegex(pin, r"^[a-f0-9]{64}$")

    def test_sideload_releases_need_no_user_supplied_secrets(self):
        workflow = (ROOT / ".github/workflows/android.yml").read_text(encoding="utf-8")
        self.assertFalse(
            "secrets." in workflow,
            "Sideload releases must build without user-supplied signing Secrets",
        )


if __name__ == "__main__":
    unittest.main()
