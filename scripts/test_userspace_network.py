#!/usr/bin/env python3
"""Build the real JNI/lwIP stack on Linux and test it with an IPv6 Ethernet peer.
Requires JDK 25, gcc and Python 3; no Android, root, TUN, USB or credentials.
"""
import argparse
import hashlib
import os
from pathlib import Path
import queue
import shutil
import socket
import struct
import subprocess
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[1]
NATIVE = ROOT / 'shared/src/main/jni'
LOCAL = socket.inet_pton(socket.AF_INET6, 'fe80::2')
PEER = socket.inet_pton(socket.AF_INET6, 'fe80::1')
LOCAL_MAC = bytes.fromhex('020000000002')
PEER_MAC = bytes.fromhex('020000000001')

def checksum(data):
    if len(data) % 2: data += b'\0'
    total = sum(struct.unpack('!' + 'H' * (len(data) // 2), data))
    while total >> 16: total = (total & 65535) + (total >> 16)
    return (~total) & 65535

def packet(protocol, body, destination=LOCAL, dst_mac=LOCAL_MAC, hops=64):
    return dst_mac + PEER_MAC + b'\x86\xdd' + struct.pack('!IHBB', 6 << 28, len(body), protocol, hops) + PEER + destination + body

def checked(protocol, body, at, destination=LOCAL):
    pseudo = PEER + destination + struct.pack('!I3xB', len(body), protocol)
    result = checksum(pseudo + body) or 65535
    return body[:at] + struct.pack('!H', result) + body[at + 2:]

def tcp(seq, ack, flags, payload=b''):
    header = struct.pack('!HHIIBBHHH', 5000, 7000, seq, ack, 5 << 4, flags, 65535, 0, 0)
    return packet(6, checked(6, header + payload, 16))

def udp(body):
    return packet(17, checked(17, struct.pack('!HHHH', 5001, 7001, len(body) + 8, 0) + body, 6))

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adapters', action='store_true', help='Also exercise the compiled Kotlin AirPlay socket adapters (build :shared:bundleLibCompileToJarDebug first)')
    args = parser.parse_args()
    java_home = Path(os.environ.get('JAVA_HOME') or Path(shutil.which('javac')).resolve().parents[1])
    with tempfile.TemporaryDirectory(prefix='diplay-network-') as directory:
        work = Path(directory)
        sources = [NATIVE / 'userspace/sys_arch.c', NATIVE / 'userspace/stack.c']
        for subdir in ['core', 'core/ipv6', 'api']: sources += sorted((NATIVE / 'vendor/lwip/src' / subdir).glob('*.c'))
        sources += [NATIVE / 'vendor/lwip/src/netif/ethernet.c']
        subprocess.run(['gcc', '-shared', '-fPIC', '-O2', '-std=c11', '-D_POSIX_C_SOURCE=200809L', '-pthread',
            '-I' + str(java_home / 'include'), '-I' + str(java_home / 'include/linux'),
            '-I' + str(NATIVE / 'userspace'), '-I' + str(NATIVE / 'vendor/lwip/src/include'),
            *map(str, sources), '-o', str(work / 'libdiplay_userspace.so')], check=True)
        classpath = str(work)
        if args.adapters:
            shared = ROOT / 'shared/build/intermediates/compile_library_classes_jar/debug/bundleLibCompileToJarDebug/classes.jar'
            gradle_home = Path(os.environ.get('GRADLE_USER_HOME', Path.home()/'.gradle'))
            kotlin = list(gradle_home.glob('wrapper/dists/*/*/*/lib/kotlin-stdlib-*.jar'))
            if not shared.exists() or not kotlin: raise SystemExit('Build :shared:bundleLibCompileToJarDebug first, with GRADLE_USER_HOME exported')
            classpath += os.pathsep + str(shared) + os.pathsep + str(kotlin[0])
            driver = 'AdapterSmoke'
            source = ROOT / 'scripts/native-tests/AdapterSmoke.java'
        else:
            driver = 'com.shilapi.xcertplay.network.userspace.UserSpaceNative'
            source = ROOT / 'scripts/native-tests/UserSpaceNative.java'
        subprocess.run([str(java_home / 'bin/javac'), '-cp', classpath, '-d', str(work), str(source)], check=True)
        process = subprocess.Popen([str(java_home / 'bin/java'), '-Djava.library.path=' + str(work), '-cp', classpath,
            driver], stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        events = queue.Queue()
        reader = threading.Thread(target=lambda: [events.put(line.strip()) for line in process.stdout], daemon=True)
        reader.start()
        frames = []
        observed = []
        def inject(frame):
            process.stdin.write('IN ' + frame.hex() + '\n'); process.stdin.flush()
        def wait(predicate, timeout=6):
            end = time.monotonic() + timeout
            while time.monotonic() < end:
                if process.poll() is not None: raise AssertionError(f'JNI driver exited {process.returncode}; {observed}')
                try: event = events.get(timeout=min(.2, max(.001, end - time.monotonic())))
                except queue.Empty: continue
                observed.append(event)
                if event.startswith('FRAME '):
                    frame = bytes.fromhex(event[6:]); frames.append(frame)
                    if len(frame) >= 54 and frame[20] in (6, 17, 58):
                        ip, body = frame[14:54], frame[54:]
                        pseudo = ip[8:40] + struct.pack('!I3xB', len(body), ip[6])
                        assert checksum(pseudo + body) == 0, 'Outgoing IPv6 transport checksum invalid'
                if predicate(event): return event
            raise AssertionError(f'Timed out; latest events: {observed[-8:]}')
        try:
            wait(lambda e: e == 'READY')
            assert 'TIMEOUT_OK' in observed and 'CLOSE_WAKE_OK' in observed
            # Real ICMPv6 neighbor solicitation/advertisement, including checksum and hop limit.
            multicast = socket.inet_pton(socket.AF_INET6, 'ff02::1:ff00:2')
            ns = struct.pack('!BBHI', 135, 0, 0, 0) + LOCAL + bytes([1, 1]) + PEER_MAC
            inject(packet(58, checked(58, ns, 2, multicast), multicast, bytes.fromhex('3333ff000002'), 255))
            wait(lambda e: e.startswith('FRAME ') and bytes.fromhex(e[6:])[20] == 58 and bytes.fromhex(e[6:])[54] == 136)
            print('PASS: IPv6 neighbor discovery')
            # UDP checksum rejection must not deliver a packet; only the valid payload is echoed.
            corrupt = bytearray(udp(b'bad-checksum')); corrupt[-1] ^= 1; inject(corrupt)
            inject(udp(b'carplay-audio-probe'))
            event = wait(lambda e: e.startswith('UDP_RX '))
            assert bytes.fromhex(event[7:])[18:] == b'carplay-audio-probe'
            wait(lambda e: e.startswith('FRAME ') and bytes.fromhex(e[6:])[20] == 17 and bytes.fromhex(e[6:])[62:] == b'carplay-audio-probe')
            print('PASS: bidirectional UDP, checksum validation')
            # Out-of-order IPv6 fragments must reassemble correctly on both 32- and 64-bit ports.
            large = bytes(range(256)) * 7
            datagram = checked(17, struct.pack('!HHHH', 5001, 7001, len(large)+8, 0) + large, 6)
            boundary = 1024
            identifier = 0x43504c59
            inject(packet(44, struct.pack('!BBHI', 17, 0, boundary, identifier) + datagram[boundary:]))
            inject(packet(44, struct.pack('!BBHI', 17, 0, 1, identifier) + datagram[:boundary]))
            event = wait(lambda e: e.startswith('UDP_RX '))
            assert bytes.fromhex(event[7:])[18:] == large
            parts = {}
            last = None
            while last is None or sum(map(len, parts.values())) < last:
                frame = bytes.fromhex(wait(lambda e: e.startswith('FRAME ') and bytes.fromhex(e[6:])[20] == 44)[6:])
                offset_flags = struct.unpack('!H', frame[56:58])[0]
                offset = offset_flags & 0xfff8
                parts[offset] = frame[62:]
                if not offset_flags & 1: last = offset + len(parts[offset])
            echoed = b''.join(parts[offset] for offset in sorted(parts))
            assert echoed[8:] == large
            assert checksum(LOCAL + PEER + struct.pack('!I3xB', len(echoed), 17) + echoed) == 0
            print('PASS: IPv6 fragment reassembly and fragmented UDP output')
            # Three-way handshake and out-of-order receive across the actual TCP stack.
            seq = 1001
            inject(tcp(seq - 1, 0, 2))
            syn = bytes.fromhex(wait(lambda e: e.startswith('FRAME ') and bytes.fromhex(e[6:])[20] == 6 and bytes.fromhex(e[6:])[67] & 0x12 == 0x12)[6:])
            ack = struct.unpack('!I', syn[58:62])[0] + 1
            inject(tcp(seq, ack, 16))
            wait(lambda e: e.startswith('TCP_ACCEPT '))
            payload = b'carplay-video-stream-probe'
            split = 10
            inject(tcp(seq + split, ack, 24, payload[split:]))
            inject(tcp(seq, ack, 24, payload[:split]))
            received = bytearray()
            # Deliberately omit the first data ACK and verify retransmission.
            first_data = None
            end = time.monotonic() + 12
            while time.monotonic() < end:
                event = wait(lambda e: e.startswith('FRAME ') and bytes.fromhex(e[6:])[20] == 6, timeout=12)
                frame = bytes.fromhex(event[6:]); data_offset = (frame[66] >> 4) * 4
                data = frame[54 + data_offset:]
                if not data: continue
                segment_seq = struct.unpack('!I', frame[58:62])[0]
                if first_data is None:
                    first_data = (segment_seq, data)
                    continue
                if (segment_seq, data) == first_data:
                    print('PASS: TCP retransmission after withheld ACK')
                    received.extend(data); ack = segment_seq + len(data)
                    inject(tcp(seq + len(payload), ack, 16))
                    break
            assert first_data is not None and received, 'TCP retransmission was not observed'
            while len(received) < len(payload):
                event = wait(lambda e: e.startswith('FRAME ') and bytes.fromhex(e[6:])[20] == 6)
                frame = bytes.fromhex(event[6:]); offset = (frame[66] >> 4) * 4
                data = frame[54 + offset:]; segment_seq = struct.unpack('!I', frame[58:62])[0]
                if data and segment_seq == ack:
                    received.extend(data); ack += len(data); inject(tcp(seq + len(payload), ack, 16))
            assert received == payload
            inject(tcp(seq + len(payload), ack, 17))
            wait(lambda e: e == f'TCP_EOF {len(payload)}')
            print('PASS: TCP handshake, out-of-order reassembly, echo and peer EOF')
            process.stdin.write('STOP\n'); process.stdin.flush()
            # Driver may exit before the queue is drained; communicate preserves its exit status.
            process.wait(timeout=6)
            reader.join(2)
            remaining = []
            while not events.empty(): remaining.append(events.get_nowait())
            assert process.returncode == 0
            assert 'REATTACH_OK' in remaining + observed, 'Detach/reattach failed'
            print('PASS: receive timeout, close wakes accept, detach/reattach')
        finally:
            if process.poll() is None: process.kill()
            process.wait()

if __name__ == '__main__': main()
