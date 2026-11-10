#!/usr/bin/env python3
"""Laboratory only: expose one real SQLite connection to the Kotlin contract runner.
No network, no application data, no dependency downloads. Binary stdin/stdout protocol.
"""
import sqlite3
import struct
import sys

source, sink = sys.stdin.buffer, sys.stdout.buffer

def read_exact(n):
    value = source.read(n)
    if len(value) != n:
        raise EOFError()
    return value

def integer():
    return struct.unpack('>i', read_exact(4))[0]

def raw():
    n = integer()
    if not 0 <= n <= 4 * 1024 * 1024:
        raise ValueError('Bridge value exceeds limit')
    return read_exact(n)

def value():
    tag = read_exact(1)
    if tag == b'N': return None
    if tag == b'L': return struct.unpack('>q', read_exact(8))[0]
    if tag == b'S': return raw().decode('utf-8', errors='strict')
    if tag == b'B': return raw()
    raise ValueError('Invalid value tag')

def write_raw(data):
    sink.write(struct.pack('>i', len(data)))
    sink.write(data)

def write_value(item):
    if item is None: sink.write(b'N')
    elif isinstance(item, int): sink.write(b'L' + struct.pack('>q', item))
    elif isinstance(item, bytes): sink.write(b'B'); write_raw(item)
    elif isinstance(item, str): sink.write(b'S'); write_raw(item.encode('utf-8'))
    else: raise ValueError('Unsupported SQLite type')

connection = sqlite3.connect(sys.argv[1], isolation_level=None, timeout=5)
connection.execute('PRAGMA foreign_keys=ON')
try:
    while True:
        opcode = source.read(1)
        if not opcode or opcode == b'X': break
        try:
            sql = raw().decode('utf-8')
            count = integer()
            if not 0 <= count <= 100: raise ValueError('Too many arguments')
            args = [value() for _ in range(count)]
            cursor = connection.execute(sql, args)
            rows = cursor.fetchall() if opcode == b'Q' else []
            sink.write(b'\x00' + struct.pack('>i', len(rows)))
            for row in rows:
                sink.write(struct.pack('>i', len(row)))
                for item in row: write_value(item)
        except Exception as error:
            sink.write(b'\x01')
            write_raw((type(error).__name__ + ': ' + str(error)).encode('utf-8'))
        sink.flush()
finally:
    connection.close()
