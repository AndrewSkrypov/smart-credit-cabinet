#!/usr/bin/env python3
import hashlib
import hmac
import ipaddress
import os
from pathlib import Path
import select
import socket
import socketserver
import ssl
import threading
import time
from http.server import BaseHTTPRequestHandler


TELEGRAM_HOST = "api.telegram.org"
TELEGRAM_PORT = 443
DESTINATION = f"{TELEGRAM_HOST}:{TELEGRAM_PORT}"


def connect_telegram():
    addresses = socket.getaddrinfo(TELEGRAM_HOST, TELEGRAM_PORT, socket.AF_INET, socket.SOCK_STREAM)
    endpoints = tuple(dict.fromkeys(entry[4] for entry in addresses))
    for attempt in range(8):
        # Retry only socket establishment, before TLS or application bytes.
        upstream = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        upstream.settimeout(2)
        try:
            upstream.connect(endpoints[attempt % len(endpoints)])
            return upstream
        except OSError:
            upstream.close()
    raise OSError("Telegram TCP unavailable")


class TunnelRequest(BaseHTTPRequestHandler):
    def setup(self):
        self.request.settimeout(5)
        if self.server.tls_context is not None:
            self.request = self.server.tls_context.wrap_socket(self.request, server_side=True)
            self.connection = self.request
        super().setup()

    def finish(self):
        try:
            super().finish()
        finally:
            if self.server.tls_context is not None:
                self.connection.close()

    def log_message(self, *args):
        # Never record request data, authorization or any tunneled bytes.
        pass

    def do_CONNECT(self):
        headers = self.headers.get_all("Proxy-Authorization", failobj=[])
        authorized = len(headers) == 1 and hmac.compare_digest(
            hashlib.sha256(headers[0].encode("latin-1")).digest(), self.server.authorization_hash
        )
        if not authorized:
            self.send_response_only(407)
            self.send_header("Proxy-Authenticate", 'Basic realm="PUMP Telegram"')
            self.end_headers()
            return
        if self.path != DESTINATION:
            self.send_response_only(403)
            self.end_headers()
            return
        try:
            upstream = connect_telegram()
        except OSError:
            self.send_response_only(502)
            self.end_headers()
            return

        with upstream:
            self.send_response_only(200, "Connection Established")
            self.end_headers()
            self.connection.settimeout(10)
            upstream.settimeout(10)
            deadline = time.monotonic() + 60
            while time.monotonic() < deadline:
                # An outer TLS socket can already have decrypted bytes buffered.
                if isinstance(self.connection, ssl.SSLSocket) and self.connection.pending():
                    readable = [self.connection]
                else:
                    readable, _, _ = select.select([self.connection, upstream], [], [], 1)
                for reader in readable:
                    data = reader.recv(16384)
                    if not data:
                        return
                    writer = upstream if reader is self.connection else self.connection
                    writer.sendall(data)


class TunnelServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, address, client_networks, auth_hash, tls_context):
        self.client_networks = client_networks
        self.authorization_hash = auth_hash
        self.tls_context = tls_context
        self.connections = threading.BoundedSemaphore(16)
        super().__init__(address, TunnelRequest)

    def verify_request(self, request, address):
        ip = ipaddress.IPv4Address(address[0])
        return any(ip in network for network in self.client_networks)

    def process_request(self, request, address):
        if not self.connections.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, address)
        except Exception:
            self.connections.release()
            raise

    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self.connections.release()

    def handle_error(self, request, address):
        # Connection failures must not send exception data to journald.
        pass


def main():
    credentials = Path(os.environ["CREDENTIALS_DIRECTORY"])
    address = ipaddress.IPv4Address(os.environ["RELAY_BIND_IP"])
    port = int(os.environ["RELAY_PORT"])
    networks = tuple(ipaddress.IPv4Network(item) for item in os.environ["RELAY_CLIENT_CIDRS"].split(","))
    authorization_hash = bytes.fromhex((credentials / "authorization-sha256").read_text().strip())
    if not networks or len(authorization_hash) != 32 or address.is_unspecified:
        raise ValueError("missing explicit relay bind, clients or authorization")

    tls_context = None
    if (credentials / "tls-key").exists():
        tls_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls_context.minimum_version = ssl.TLSVersion.TLSv1_2
        tls_context.load_cert_chain(credentials / "tls-cert", credentials / "tls-key")
    elif not address.is_private or any(not network.is_private for network in networks):
        raise ValueError("TLS is required for a public endpoint")

    with TunnelServer((str(address), port), networks, authorization_hash, tls_context) as server:
        server.serve_forever()


if __name__ == "__main__":
    main()
