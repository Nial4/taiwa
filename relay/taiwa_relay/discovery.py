"""Advertise the relay on the LAN (Bonjour/mDNS `_taiwa._tcp`) so the TV can find this computer
again after its IP address changes. The TV still needs the relay token; discovery is not auth.

- macOS: registration goes through the system's mDNSResponder (`dns-sd -R`). It follows address
  changes itself, and a Python process sending multicast directly is blocked by macOS's Local
  Network privacy when it runs as a background agent.
- Linux: `avahi-publish-service` when Avahi is installed.
- Otherwise (Windows, Linux without Avahi): the python-zeroconf package.
"""

import asyncio
import logging
import os
import shutil
import socket
import sys

from . import config

log = logging.getLogger("taiwa.discovery")

SERVICE_TYPE = "_taiwa._tcp"


def method() -> str:
    if os.environ.get("TAIWA_MDNS", "1") == "0":
        return "none"
    if sys.platform == "darwin" and shutil.which("dns-sd"):
        return "dns-sd"
    if shutil.which("avahi-publish-service"):
        return "avahi"
    try:
        import zeroconf  # noqa: F401
        return "zeroconf"
    except ImportError:
        return "none"


def lan_ip() -> str | None:
    """Address of the interface that routes to the LAN (no packets are sent)."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("192.168.0.1", 9))
        return s.getsockname()[0]
    except OSError:
        return None
    finally:
        s.close()


def _name() -> str:
    return f"Taiwa on {socket.gethostname().split('.')[0]}"


async def advertise() -> None:
    """Keep the service registered; runs until cancelled."""
    how = method()
    if how == "zeroconf":
        await _advertise_zeroconf()
        return
    if how == "none":
        log.warning("no mDNS publisher available; the TV will need the address entered by hand")
        return
    cmd = (["dns-sd", "-R", _name(), SERVICE_TYPE, "local", str(config.LISTEN_PORT), "v=1"] if how == "dns-sd"
           else ["avahi-publish-service", _name(), SERVICE_TYPE, str(config.LISTEN_PORT), "v=1"])
    while True:
        proc = await asyncio.create_subprocess_exec(
            *cmd, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT)
        try:
            async for line in proc.stdout:
                text = line.decode(errors="replace").strip()
                if any(w in text.lower() for w in ("registered", "established", "error", "fail")):
                    log.info("%s: %s", how, text)
            log.warning("%s exited with %s; restarting", how, await proc.wait())
        except asyncio.CancelledError:
            proc.terminate()
            await proc.wait()
            raise
        await asyncio.sleep(10)


async def _advertise_zeroconf() -> None:
    from zeroconf import IPVersion, ServiceInfo
    from zeroconf.asyncio import AsyncZeroconf

    zc = AsyncZeroconf(ip_version=IPVersion.V4Only)
    current = None
    try:
        while True:
            ip = lan_ip()
            if ip and (current is None or current.parsed_addresses() != [ip]):
                if current is not None:
                    await zc.async_unregister_service(current)
                host = socket.gethostname().split(".")[0]
                current = ServiceInfo(f"{SERVICE_TYPE}.local.", f"{_name()}.{SERVICE_TYPE}.local.",
                                      addresses=[socket.inet_aton(ip)], port=config.LISTEN_PORT,
                                      properties={"v": "1"}, server=f"{host}.local.")
                await zc.async_register_service(current)
                log.info("zeroconf: advertising at %s:%d", ip, config.LISTEN_PORT)
            await asyncio.sleep(60)
    finally:
        if current is not None:
            await zc.async_unregister_service(current)
        await zc.async_close()
