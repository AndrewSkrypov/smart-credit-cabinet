"""Render the maintained gateway source as one file for deployment and reliability probes."""

from pathlib import Path
import re


MODULE_PATH = "/etc/nginx/cabinet-gateway"
MODULE_INCLUDE = re.compile(rf"^[ \t]*include {re.escape(MODULE_PATH)}/([a-z-]+\.conf);[ \t]*$", re.MULTILINE)


def expand_gateway_modules(config, read_module):
    return MODULE_INCLUDE.sub(lambda match: read_module(match[1]).rstrip("\r\n"), config)


def gateway_source(root: Path):
    directory = root / "docker/gateway"
    config = (directory / "nginx.conf").read_text(encoding="utf-8")
    return expand_gateway_modules(config, lambda name: (directory / "conf.d" / name).read_text(encoding="utf-8"))


def running_gateway_source(docker, container):
    config = docker("exec", container, "cat", "/etc/nginx/conf.d/default.conf")
    return expand_gateway_modules(config, lambda name: docker("exec", container, "cat", f"{MODULE_PATH}/{name}"))
