"""Read Radmin peers from Windows' IPv4 neighbor table without admin tools."""
import ctypes
import ipaddress
import sys


RADMIN_NETWORK = ipaddress.ip_network('26.0.0.0/8')


class _Neighbor(ctypes.Structure):
    # MIB_IPNETROW: https://learn.microsoft.com/windows/win32/api/ipmib/ns-ipmib-mib_ipnetrow_w2k
    _fields_ = [('index', ctypes.c_uint32), ('physical_length', ctypes.c_uint32),
                ('physical_address', ctypes.c_ubyte * 8),
                ('address', ctypes.c_ubyte * 4), ('kind', ctypes.c_uint32)]


class _NeighborTable(ctypes.Structure):
    _fields_ = [('count', ctypes.c_uint32), ('rows', _Neighbor * 1)]


def get_radmin_peers(interface_indices):
    """Return only IPv4 peers already known on the selected Radmin adapters."""
    indices = {int(index) for index in interface_indices if int(index) > 0}
    if sys.platform != 'win32' or not indices:
        return set()
    try:
        read_table = ctypes.WinDLL('iphlpapi.dll').GetIpNetTable
        read_table.argtypes = [ctypes.c_void_p, ctypes.POINTER(ctypes.c_uint32), ctypes.c_int]
        read_table.restype = ctypes.c_uint32
        size = ctypes.c_uint32()
        status = read_table(None, ctypes.byref(size), False)
        for _ in range(3):
            if status != 122 or not size.value:  # ERROR_INSUFFICIENT_BUFFER
                return set()
            buffer = ctypes.create_string_buffer(size.value)
            status = read_table(buffer, ctypes.byref(size), False)
            if status == 0:
                break
        if status != 0:
            return set()
        count = ctypes.c_uint32.from_buffer(buffer).value
        offset = _NeighborTable.rows.offset
        if offset + count * ctypes.sizeof(_Neighbor) > len(buffer):
            return set()
        rows = (_Neighbor * count).from_buffer(buffer, offset)
        peers = set()
        for row in rows:
            if row.index not in indices:
                continue
            address = ipaddress.IPv4Address(bytes(row.address))
            if address in RADMIN_NETWORK and address not in (RADMIN_NETWORK.network_address, RADMIN_NETWORK.broadcast_address):
                peers.add(str(address))
        return peers
    except (OSError, ValueError, TypeError):
        return set()
