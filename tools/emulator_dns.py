"""A test-only DNS forwarder on 127.0.0.1:53, for the emulator to reach both the tailnet and Google.

Tailscale's resolver (100.100.100.100) on the test PC answers only tailnet names, and a public
resolver can't answer those, so each query goes to the one that can: *.ts.net to Tailscale,
everything else to 8.8.8.8. Push needs both: the dashboard and relay are on the tailnet, Firebase
is not. UDP only; the raw packet is forwarded and the answer relayed back.

    python -I tools/emulator_dns.py
    emulator -avd HermesAVD -dns-server 127.0.0.1
"""
import socket
import threading

TAILNET = ("100.100.100.100", 53)
PUBLIC = ("8.8.8.8", 53)


def qname(packet: bytes) -> str:
    labels, i = [], 12
    while i < len(packet) and packet[i] != 0:
        n = packet[i]
        labels.append(packet[i + 1:i + 1 + n].decode("ascii", "replace"))
        i += 1 + n
    return ".".join(labels).lower()


def answer(server: socket.socket, packet: bytes, client) -> None:
    upstream = TAILNET if qname(packet).endswith(".ts.net") else PUBLIC
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.settimeout(5)
        try:
            s.sendto(packet, upstream)
            reply, _ = s.recvfrom(4096)
            server.sendto(reply, client)
        except OSError:
            pass


def main() -> None:
    server = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    server.bind(("127.0.0.1", 53))
    print("listening on 127.0.0.1:53", flush=True)
    while True:
        packet, client = server.recvfrom(4096)
        threading.Thread(target=answer, args=(server, packet, client), daemon=True).start()


if __name__ == "__main__":
    main()
