"""Supervise one client launch in a Windows job and collect visible error text.

The job owns only this launch and its descendants. Closing it terminates these
processes, never another copy of the game or a process selected by port/name.
"""
import argparse
import ctypes
from ctypes import wintypes as w
import json
from pathlib import Path
import subprocess
import time

kernel = ctypes.WinDLL('kernel32', use_last_error=True)
user = ctypes.WinDLL('user32', use_last_error=True)


class STARTUPINFO(ctypes.Structure):
    _fields_ = [('cb', w.DWORD), ('lpReserved', w.LPWSTR), ('lpDesktop', w.LPWSTR),
                ('lpTitle', w.LPWSTR), ('dwX', w.DWORD), ('dwY', w.DWORD),
                ('dwXSize', w.DWORD), ('dwYSize', w.DWORD), ('dwXCountChars', w.DWORD),
                ('dwYCountChars', w.DWORD), ('dwFillAttribute', w.DWORD),
                ('dwFlags', w.DWORD), ('wShowWindow', w.WORD), ('cbReserved2', w.WORD),
                ('lpReserved2', ctypes.POINTER(w.BYTE)), ('hStdInput', w.HANDLE),
                ('hStdOutput', w.HANDLE), ('hStdError', w.HANDLE)]


class PROCESS_INFORMATION(ctypes.Structure):
    _fields_ = [('hProcess', w.HANDLE), ('hThread', w.HANDLE),
                ('dwProcessId', w.DWORD), ('dwThreadId', w.DWORD)]


class BASIC_LIMIT(ctypes.Structure):
    _fields_ = [('PerProcessUserTimeLimit', ctypes.c_int64), ('PerJobUserTimeLimit', ctypes.c_int64),
                ('LimitFlags', w.DWORD), ('MinimumWorkingSetSize', ctypes.c_size_t),
                ('MaximumWorkingSetSize', ctypes.c_size_t), ('ActiveProcessLimit', w.DWORD),
                ('Affinity', ctypes.c_size_t), ('PriorityClass', w.DWORD), ('SchedulingClass', w.DWORD)]


class IO_COUNTERS(ctypes.Structure):
    _fields_ = [(name, ctypes.c_uint64) for name in ('ReadOperationCount', 'WriteOperationCount',
                'OtherOperationCount', 'ReadTransferCount', 'WriteTransferCount', 'OtherTransferCount')]


class EXTENDED_LIMIT(ctypes.Structure):
    _fields_ = [('BasicLimitInformation', BASIC_LIMIT), ('IoInfo', IO_COUNTERS),
                ('ProcessMemoryLimit', ctypes.c_size_t), ('JobMemoryLimit', ctypes.c_size_t),
                ('PeakProcessMemoryUsed', ctypes.c_size_t), ('PeakJobMemoryUsed', ctypes.c_size_t)]


kernel.CreateJobObjectW.argtypes = [ctypes.c_void_p, w.LPCWSTR]
kernel.CreateJobObjectW.restype = w.HANDLE
kernel.SetInformationJobObject.argtypes = [w.HANDLE, ctypes.c_int, ctypes.c_void_p, w.DWORD]
kernel.SetInformationJobObject.restype = w.BOOL
kernel.QueryInformationJobObject.argtypes = [w.HANDLE, ctypes.c_int, ctypes.c_void_p, w.DWORD, ctypes.c_void_p]
kernel.QueryInformationJobObject.restype = w.BOOL
kernel.AssignProcessToJobObject.argtypes = [w.HANDLE, w.HANDLE]
kernel.AssignProcessToJobObject.restype = w.BOOL
kernel.CreateProcessW.argtypes = [w.LPCWSTR, w.LPWSTR, ctypes.c_void_p, ctypes.c_void_p,
                                 w.BOOL, w.DWORD, ctypes.c_void_p, w.LPCWSTR,
                                 ctypes.POINTER(STARTUPINFO), ctypes.POINTER(PROCESS_INFORMATION)]
kernel.CreateProcessW.restype = w.BOOL
kernel.ResumeThread.argtypes = [w.HANDLE]
kernel.ResumeThread.restype = w.DWORD
kernel.TerminateProcess.argtypes = [w.HANDLE, w.UINT]
kernel.CloseHandle.argtypes = [w.HANDLE]
kernel.GetExitCodeProcess.argtypes = [w.HANDLE, ctypes.POINTER(w.DWORD)]
user.GetWindowThreadProcessId.argtypes = [w.HWND, ctypes.POINTER(w.DWORD)]
user.GetWindowTextLengthW.argtypes = [w.HWND]
user.GetWindowTextW.argtypes = [w.HWND, w.LPWSTR, ctypes.c_int]
CALLBACK = ctypes.WINFUNCTYPE(w.BOOL, w.HWND, w.LPARAM)
user.EnumWindows.argtypes = [CALLBACK, w.LPARAM]
user.EnumChildWindows.argtypes = [w.HWND, CALLBACK, w.LPARAM]


def checked(result):
    if not result:
        raise ctypes.WinError(ctypes.get_last_error())
    return result


def job_pids(job):
    # JobObjectBasicProcessIdList: DWORD count, DWORD listed, ULONG_PTR ids[].
    buf = ctypes.create_string_buffer(8 + ctypes.sizeof(ctypes.c_size_t) * 256)
    checked(kernel.QueryInformationJobObject(job, 3, buf, len(buf), None))
    count = w.DWORD.from_buffer(buf, 4).value
    return {ctypes.c_size_t.from_buffer(buf, 8 + i * ctypes.sizeof(ctypes.c_size_t)).value for i in range(count)}


def window_text(pids):
    windows = []
    def text(hwnd):
        length = user.GetWindowTextLengthW(hwnd)
        buf = ctypes.create_unicode_buffer(min(length, 8192) + 1)
        user.GetWindowTextW(hwnd, buf, len(buf))
        return buf.value
    @CALLBACK
    def enum(hwnd, _):
        pid = w.DWORD()
        user.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        if pid.value not in pids:
            return True
        controls = []
        @CALLBACK
        def child(handle, _):
            value = text(handle)
            if value:
                controls.append(value)
            return True
        user.EnumChildWindows(hwnd, child, 0)
        windows.append({'pid': pid.value, 'title': text(hwnd), 'controls': controls})
        return True
    checked(user.EnumWindows(enum, 0))
    return windows


def supervise(executable, arguments, directory, output, timeout=None):
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    job = checked(kernel.CreateJobObjectW(None, None))
    process = PROCESS_INFORMATION()
    try:
        limits = EXTENDED_LIMIT()
        limits.BasicLimitInformation.LimitFlags = 0x2000  # KILL_ON_JOB_CLOSE
        checked(kernel.SetInformationJobObject(job, 9, ctypes.byref(limits), ctypes.sizeof(limits)))
        startup = STARTUPINFO()
        startup.cb = ctypes.sizeof(startup)
        command = ctypes.create_unicode_buffer(subprocess.list2cmdline([str(executable), *arguments]))
        checked(kernel.CreateProcessW(str(executable), command, None, None, False, 4, None,
                                      str(directory), ctypes.byref(startup), ctypes.byref(process)))
        try:
            checked(kernel.AssignProcessToJobObject(job, process.hProcess))
            if kernel.ResumeThread(process.hThread) == 0xffffffff:
                raise ctypes.WinError(ctypes.get_last_error())
        except BaseException:
            kernel.TerminateProcess(process.hProcess, 1)
            raise
        (output / 'client.json').write_text(json.dumps({'pid': process.dwProcessId,
                                                       'started': time.time()}), encoding='utf-8')
        began, last, timed_out = time.monotonic(), None, False
        with (output / 'windows.jsonl').open('a', encoding='utf-8') as log:
            while True:
                pids = job_pids(job)
                if not pids:
                    break
                try:
                    windows = window_text(pids)
                except OSError as exc:
                    # Noninteractive desktops can deny window enumeration.
                    # Record this limitation without terminating the client.
                    windows = [{'capture_error': str(exc)}]
                current = json.dumps({'pids': sorted(pids), 'windows': windows}, ensure_ascii=False)
                if current != last:
                    log.write(json.dumps({'time': time.time(), 'state': json.loads(current)}, ensure_ascii=False) + '\n')
                    log.flush()
                    last = current
                if timeout is not None and time.monotonic() - began > timeout:
                    timed_out = True
                    break
                time.sleep(.25)
        exit_code = w.DWORD()
        checked(kernel.GetExitCodeProcess(process.hProcess, ctypes.byref(exit_code)))
        result = {'root_exit_code': exit_code.value, 'timed_out': timed_out, 'ended': time.time()}
        (output / 'client-exit.json').write_text(json.dumps(result), encoding='utf-8')
        return 124 if timed_out else (exit_code.value & 0x7fffffff)
    finally:
        if process.hThread:
            kernel.CloseHandle(process.hThread)
        if process.hProcess:
            kernel.CloseHandle(process.hProcess)
        kernel.CloseHandle(job)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', required=True)
    parser.add_argument('--root', required=True)
    parser.add_argument('--nick', required=True)
    parser.add_argument('--server', required=True)
    args = parser.parse_args()
    root = Path(args.root).resolve()
    raise SystemExit(supervise(root / 'Wolfteam.exe', [args.nick, args.server, '40706', '1'],
                              root, args.output))
