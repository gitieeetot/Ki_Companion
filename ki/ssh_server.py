from __future__ import annotations

import base64
from dataclasses import dataclass
import ipaddress
import json
import os
import re
import shutil
import subprocess
import threading

from PySide6.QtCore import QObject, Signal


TAILSCALE_IPV4_NETWORK = ipaddress.ip_network("100.64.0.0/10")
PRIVATE_SSH_NETWORKS = (
    ipaddress.ip_network("10.0.0.0/8"),
    ipaddress.ip_network("172.16.0.0/12"),
    ipaddress.ip_network("192.168.0.0/16"),
)


def create_ssh_qr_payload(host: str, username: str, port: int) -> str:
    try:
        address = ipaddress.ip_address(host)
    except ValueError as error:
        raise ValueError("Choose a private IPv4 address for the SSH QR.") from error
    if address.version != 4 or not (
        address in TAILSCALE_IPV4_NETWORK
        or any(address in network for network in PRIVATE_SSH_NETWORKS)
    ):
        raise ValueError("The SSH QR can contain only a private LAN or Tailscale IPv4 address.")
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,64}", username):
        raise ValueError("Enter a valid Windows username for the SSH QR.")
    if type(port) is not int or not 1 <= port <= 65535:
        raise ValueError("Enter a valid SSH port for the QR.")
    return json.dumps(
        {"type": "ki-ssh", "host": str(address), "username": username, "port": port},
        separators=(",", ":"),
    )


@dataclass(frozen=True)
class OpenSSHStatus:
    installed: bool
    running: bool
    tailscale_state: str
    tailscale_ipv4: str | None
    error: str = ""


class OpenSSHManager(QObject):
    operation_finished = Signal(str, bool, str)

    def __init__(self, parent=None):
        super().__init__(parent)
        self._operation_lock = threading.Lock()

    def status(self) -> OpenSSHStatus:
        if os.name != "nt":
            return OpenSSHStatus(False, False, "Unavailable", None, "OpenSSH service controls are available on Windows.")

        sc = shutil.which("sc.exe")
        if not sc:
            return OpenSSHStatus(False, False, "Unavailable", None, "Windows Service Control is unavailable.")
        try:
            service = subprocess.run(
                [sc, "query", "sshd"],
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=4,
                check=False,
            )
        except (OSError, subprocess.SubprocessError) as error:
            return OpenSSHStatus(False, False, "Unavailable", None, f"Could not inspect Windows SSH service: {error}")

        output = service.stdout + service.stderr
        if service.returncode != 0:
            if "1060" in output or "does not exist" in output.casefold():
                installed = False
                running = False
            else:
                return OpenSSHStatus(False, False, "Unavailable", None, output.strip() or "Could not inspect Windows SSH service.")
        else:
            installed = True
            running = "RUNNING" in output.upper()

        tailscale_state, tailscale_ipv4 = self._tailscale_status()
        return OpenSSHStatus(installed, running, tailscale_state, tailscale_ipv4)

    @staticmethod
    def _tailscale_status() -> tuple[str, str | None]:
        executable = shutil.which("tailscale.exe") or shutil.which("tailscale")
        if not executable:
            for candidate in (
                os.path.join(os.environ.get("ProgramFiles", r"C:\Program Files"), "Tailscale IPN", "tailscale.exe"),
                os.path.join(os.environ.get("ProgramFiles", r"C:\Program Files"), "Tailscale", "tailscale.exe"),
            ):
                if os.path.isfile(candidate):
                    executable = candidate
                    break
        if not executable:
            return "Not installed", None
        try:
            result = subprocess.run(
                [executable, "ip", "-4"],
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=4,
                check=False,
            )
        except (OSError, subprocess.SubprocessError):
            return "Unavailable", None
        if result.returncode == 0:
            for candidate in result.stdout.split():
                try:
                    address = ipaddress.ip_address(candidate)
                except ValueError:
                    continue
                if address.version == 4 and address in TAILSCALE_IPV4_NETWORK:
                    return "Connected", str(address)
        return "Offline", None

    def start_async(self, port: int = 22) -> bool:
        if type(port) is not int or not 1 <= port <= 65535:
            raise ValueError("SSH port must be between 1 and 65535.")
        return self._run_async("start", port)

    def stop_async(self) -> bool:
        return self._run_async("stop", 22)

    def _run_async(self, action: str, port: int) -> bool:
        if action not in {"start", "stop"}:
            raise ValueError("Unsupported OpenSSH service action.")
        if not self._operation_lock.acquire(blocking=False):
            return False
        threading.Thread(
            target=self._run_operation,
            args=(action, port),
            name=f"ki-openssh-{action}",
            daemon=True,
        ).start()
        return True

    def _run_operation(self, action: str, port: int) -> None:
        try:
            status = self.status()
            if status.error:
                raise RuntimeError(status.error)
            if not status.installed:
                raise RuntimeError(
                    "Windows OpenSSH Server is not installed. Install it from Settings → System → Optional features, "
                    "then reopen Ki's phone companion."
                )
            if action == "stop" and not status.running:
                message = "OpenSSH Server is already stopped."
            else:
                if action == "start":
                    script = (
                        "$ErrorActionPreference = 'Stop'\n"
                        "$defaultRule = Get-NetFirewallRule -Name 'OpenSSH-Server-In-TCP' -ErrorAction SilentlyContinue\n"
                        "if ($defaultRule) { $defaultRule | Disable-NetFirewallRule }\n"
                        "Get-NetFirewallRule -Name 'KiCompanion-OpenSSH-Private' -ErrorAction SilentlyContinue | Remove-NetFirewallRule\n"
                        "Get-NetFirewallRule -Name 'KiCompanion-OpenSSH-Tailscale' -ErrorAction SilentlyContinue | Remove-NetFirewallRule\n"
                        "New-NetFirewallRule -Name 'KiCompanion-OpenSSH-Private' -DisplayName 'Ki Companion SSH · Private networks' "
                        f"-Direction Inbound -Action Allow -Protocol TCP -LocalPort {port} -RemoteAddress LocalSubnet -Profile Private | Out-Null\n"
                        "New-NetFirewallRule -Name 'KiCompanion-OpenSSH-Tailscale' -DisplayName 'Ki Companion SSH · Tailscale' "
                        f"-Direction Inbound -Action Allow -Protocol TCP -LocalPort {port} -RemoteAddress '100.64.0.0/10' -Profile Any | Out-Null\n"
                        "Start-Service -Name sshd\n"
                        "if ((Get-Service -Name sshd).Status -ne 'Running') { throw 'OpenSSH Server did not start.' }\n"
                    )
                else:
                    script = (
                        "$ErrorActionPreference = 'Stop'\n"
                        "Stop-Service -Name sshd\n"
                        "if ((Get-Service -Name sshd).Status -ne 'Stopped') { throw 'OpenSSH Server did not stop.' }\n"
                    )
                self._run_elevated(script)
                if action == "start":
                    message = (
                        "OpenSSH Server started with private/Tailscale-only firewall rules."
                        if not status.running
                        else "OpenSSH Server is running; private/Tailscale-only firewall rules were refreshed."
                    )
                else:
                    message = "OpenSSH Server stopped."
            self.operation_finished.emit(action, True, message)
        except (RuntimeError, OSError, subprocess.SubprocessError) as error:
            self.operation_finished.emit(action, False, str(error))
        finally:
            self._operation_lock.release()

    @staticmethod
    def _run_elevated(script: str) -> None:
        powershell = shutil.which("powershell.exe")
        if not powershell:
            raise RuntimeError("Windows PowerShell is unavailable.")
        encoded = base64.b64encode(script.encode("utf-16le")).decode("ascii")
        launcher = (
            "$ErrorActionPreference = 'Stop'; "
            "$arguments = @('-NoProfile','-ExecutionPolicy','Bypass','-EncodedCommand', "
            f"'{encoded}'); "
            "$process = Start-Process -FilePath 'powershell.exe' -Verb RunAs -Wait -PassThru -ArgumentList $arguments; "
            "exit $process.ExitCode"
        )
        result = subprocess.run(
            [powershell, "-NoProfile", "-Command", launcher],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=120,
            check=False,
        )
        if result.returncode != 0:
            message = result.stderr.strip() or result.stdout.strip()
            if "cancel" in message.casefold():
                raise RuntimeError("Administrator approval was cancelled; OpenSSH was not changed.")
            raise RuntimeError(message or f"Administrator approval failed (exit code {result.returncode}).")
