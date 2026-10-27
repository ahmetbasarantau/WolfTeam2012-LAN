using System;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Threading;
using System.Diagnostics;
using System.Collections.Generic;

public static class D3DHook
{
    private static string s_logPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "d3d9_proxy.log");

    private static void Log(string msg)
    {
        try { File.AppendAllText(s_logPath, string.Format("[{0:HH:mm:ss.fff}] {1}\r\n", DateTime.Now, msg)); }
        catch { }
    }

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Ansi)]
    private static extern IntPtr LoadLibraryA(string lpLibFileName);

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Ansi)]
    private static extern IntPtr GetProcAddress(IntPtr hModule, string lpProcName);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool VirtualProtect(IntPtr lpAddress, UIntPtr dwSize, uint flNewProtect, out uint lpflOldProtect);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr VirtualAlloc(IntPtr lpAddress, UIntPtr dwSize, uint flAllocationType, uint flProtect);

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate IntPtr Real_Direct3DCreate9_Delegate(uint sdkVersion);

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int Direct3DCreate9Ex_Delegate(uint sdkVersion, out IntPtr ppD3D);

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int GetDeviceCaps_Delegate(IntPtr thisPtr, uint adapter, uint devType, IntPtr pCaps);

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int CheckDeviceType_Delegate(IntPtr thisPtr, uint adapter, uint devType, uint adapterFormat, uint backBufferFormat, bool windowed);

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate uint GetAdapterModeCount_Delegate(IntPtr thisPtr, uint adapter, uint format);

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int EnumAdapterModes_Delegate(IntPtr thisPtr, uint adapter, uint format, uint modeIndex, IntPtr pMode);

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int CreateDevice_Delegate(IntPtr thisPtr, uint adapter, uint devType, IntPtr hFocusWindow, uint behaviorFlags, IntPtr pPresentationParameters, IntPtr ppReturnedDeviceInterface);

    // IDirect3DDevice9 resource allocation methods
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int CreateTexture_Delegate(IntPtr dev, uint w, uint h, uint levels, uint usage, uint format, uint pool, out IntPtr ppTex, IntPtr pShared);

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int CreateVertexBuffer_Delegate(IntPtr dev, uint length, uint usage, uint fvf, uint pool, out IntPtr ppVB, IntPtr pShared);

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int CreateIndexBuffer_Delegate(IntPtr dev, uint length, uint usage, uint format, uint pool, out IntPtr ppIB, IntPtr pShared);

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int Connect_Delegate(IntPtr s, IntPtr name, int namelen);

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int WSAConnect_Delegate(IntPtr s, IntPtr name, int namelen, IntPtr lpCallerData, IntPtr lpCalleeData, IntPtr lpSQOS, IntPtr lpGQOS);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate IntPtr GetProcAddress_Delegate(IntPtr module, IntPtr name);
    [UnmanagedFunctionPointer(CallingConvention.StdCall, SetLastError=true)]
    private delegate IntPtr FindFirstFile_Delegate(IntPtr pattern, IntPtr data);
    [UnmanagedFunctionPointer(CallingConvention.StdCall, SetLastError=true)]
    private delegate bool FindNextFile_Delegate(IntPtr search, IntPtr data);
    [UnmanagedFunctionPointer(CallingConvention.StdCall, SetLastError=true)]
    private delegate bool FindClose_Delegate(IntPtr search);

    private static Real_Direct3DCreate9_Delegate s_realCreate;
    private static Direct3DCreate9Ex_Delegate s_realCreateEx;

    private static GetDeviceCaps_Delegate s_origGetDeviceCaps;
    private static CheckDeviceType_Delegate s_origCheckDeviceType;
    private static GetAdapterModeCount_Delegate s_origGetAdapterModeCount;
    private static EnumAdapterModes_Delegate s_origEnumAdapterModes;
    private static CreateDevice_Delegate s_origCreateDevice;

    private static GetDeviceCaps_Delegate s_hookGetDeviceCaps;
    private static CheckDeviceType_Delegate s_hookCheckDeviceType;
    private static GetAdapterModeCount_Delegate s_hookGetAdapterModeCount;
    private static EnumAdapterModes_Delegate s_hookEnumAdapterModes;
    private static CreateDevice_Delegate s_hookCreateDevice;

    private static CreateTexture_Delegate s_origCreateTexture;
    private static CreateTexture_Delegate s_hookCreateTexture;

    private static CreateVertexBuffer_Delegate s_origCreateVertexBuffer;
    private static CreateVertexBuffer_Delegate s_hookCreateVertexBuffer;

    private static CreateIndexBuffer_Delegate s_origCreateIndexBuffer;
    private static CreateIndexBuffer_Delegate s_hookCreateIndexBuffer;

    private static Connect_Delegate s_origConnect;
    private static Connect_Delegate s_hookConnect;
    private static WSAConnect_Delegate s_origWSAConnect;
    private static WSAConnect_Delegate s_hookWSAConnect;
    private static GetProcAddress_Delegate s_origGetProcAddress;
    private static GetProcAddress_Delegate s_hookGetProcAddress;
    private static IntPtr s_connectExport;
    private static IntPtr s_wsaConnectExport;
    private static FindFirstFile_Delegate s_originalFindFirst, s_hookFindFirst;
    private static FindNextFile_Delegate s_originalFindNext, s_hookFindNext;
    private static FindClose_Delegate s_originalFindClose, s_hookFindClose;
    private static readonly HashSet<IntPtr> s_compatDllSearches = new HashSet<IntPtr>();
    private static readonly object s_searchLock = new object();
    [DllImport("kernel32.dll", EntryPoint="SetLastError")]
    private static extern void SetNativeLastError(int error);

    [DllImport("kernel32.dll")]
    private static extern IntPtr GetCurrentProcess();
    [DllImport("kernel32.dll", SetLastError=true)]
    private static extern bool ReadProcessMemory(IntPtr process, IntPtr address, byte[] data, UIntPtr size, out UIntPtr read);
    [DllImport("kernel32.dll", SetLastError=true)]
    private static extern bool FlushInstructionCache(IntPtr process, IntPtr address, UIntPtr size);

    private static IntPtr s_newVtable = IntPtr.Zero;
    private static readonly Dictionary<ulong, byte[][]> s_displayModes = new Dictionary<ulong, byte[][]>();
    private static bool s_winsockHooked = false;
    private static bool s_deviceHooksInstalled = false;
    private static bool s_isEx = false;
    private static bool s_characterSlotsChecked = false;

    private static string s_targetServerIp = "127.0.0.1";
    private static byte[] s_targetIpBytes = new byte[] { 127, 0, 0, 1 };

    private static void ParseCommandLineTarget()
    {
        try
        {
            string cmd = Environment.CommandLine;
            Log("Process CommandLine: " + cmd);
            string[] parts = cmd.Split(new char[] { ' ', '\t' }, StringSplitOptions.RemoveEmptyEntries);
            foreach (string part in parts)
            {
                IPAddress ip;
                if (IPAddress.TryParse(part, out ip) && ip.AddressFamily == AddressFamily.InterNetwork)
                {
                    s_targetServerIp = ip.ToString();
                    s_targetIpBytes = ip.GetAddressBytes();
                    Log("Target server IP configured: " + s_targetServerIp);
                    break;
                }
            }
        }
        catch { }
    }

    public static IntPtr Direct3DCreate9_Hook(uint sdkVersion)
    {
        Log("Direct3DCreate9 called with sdkVersion=" + sdkVersion);
        try
        {
            ParseCommandLineTarget();
            InstallWinsockHooks();

            if (s_realCreateEx == null && s_realCreate == null)
            {
                IntPtr hReal = LoadLibraryA(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.SystemX86), "d3d9.dll"));
                if (hReal == IntPtr.Zero) throw new InvalidOperationException("System D3D9 unavailable");

                IntPtr pFuncEx = GetProcAddress(hReal, "Direct3DCreate9Ex");
                if (pFuncEx != IntPtr.Zero)
                {
                    s_realCreateEx = (Direct3DCreate9Ex_Delegate)Marshal.GetDelegateForFunctionPointer(pFuncEx, typeof(Direct3DCreate9Ex_Delegate));
                    Log("Direct3DCreate9Ex exported function found!");
                }
                IntPtr pFunc = GetProcAddress(hReal, "Direct3DCreate9");
                s_realCreate = (Real_Direct3DCreate9_Delegate)Marshal.GetDelegateForFunctionPointer(pFunc, typeof(Real_Direct3DCreate9_Delegate));
            }

            IntPtr pD3D = IntPtr.Zero;
            if (s_realCreateEx != null)
            {
                int hrEx = s_realCreateEx(sdkVersion, out pD3D);
                s_isEx = hrEx == 0 && pD3D != IntPtr.Zero;
                Log(string.Format("Direct3DCreate9Ex returned hr={0:X}, pD3D={1:X}", hrEx, pD3D.ToString("X")));
            }
            if (pD3D == IntPtr.Zero && s_realCreate != null)
            {
                pD3D = s_realCreate(sdkVersion);
                Log("Fallback Real Direct3DCreate9 returned pD3D=" + pD3D.ToString("X"));
            }

            if (pD3D == IntPtr.Zero)
            {
                Log("Direct3D creation failed completely!");
                return IntPtr.Zero;
            }

            IntPtr origVtable = Marshal.ReadIntPtr(pD3D);
            if (s_newVtable == IntPtr.Zero)
            {
                int slotCount = s_isEx ? 22 : 17;
                s_newVtable = Marshal.AllocHGlobal(slotCount * IntPtr.Size);
                for (int i = 0; i < slotCount; i++)
                {
                    IntPtr fn = Marshal.ReadIntPtr(origVtable, i * IntPtr.Size);
                    Marshal.WriteIntPtr(s_newVtable, i * IntPtr.Size, fn);
                }

                // Slot 6: GetAdapterModeCount
                s_origGetAdapterModeCount = (GetAdapterModeCount_Delegate)Marshal.GetDelegateForFunctionPointer(
                    Marshal.ReadIntPtr(origVtable, 6 * IntPtr.Size), typeof(GetAdapterModeCount_Delegate));
                s_hookGetAdapterModeCount = new GetAdapterModeCount_Delegate(Hook_GetAdapterModeCount);
                Marshal.WriteIntPtr(s_newVtable, 6 * IntPtr.Size, Marshal.GetFunctionPointerForDelegate(s_hookGetAdapterModeCount));

                // Slot 7: EnumAdapterModes
                s_origEnumAdapterModes = (EnumAdapterModes_Delegate)Marshal.GetDelegateForFunctionPointer(
                    Marshal.ReadIntPtr(origVtable, 7 * IntPtr.Size), typeof(EnumAdapterModes_Delegate));
                s_hookEnumAdapterModes = new EnumAdapterModes_Delegate(Hook_EnumAdapterModes);
                Marshal.WriteIntPtr(s_newVtable, 7 * IntPtr.Size, Marshal.GetFunctionPointerForDelegate(s_hookEnumAdapterModes));

                // Slot 9: CheckDeviceType
                s_origCheckDeviceType = (CheckDeviceType_Delegate)Marshal.GetDelegateForFunctionPointer(
                    Marshal.ReadIntPtr(origVtable, 9 * IntPtr.Size), typeof(CheckDeviceType_Delegate));
                s_hookCheckDeviceType = new CheckDeviceType_Delegate(Hook_CheckDeviceType);
                Marshal.WriteIntPtr(s_newVtable, 9 * IntPtr.Size, Marshal.GetFunctionPointerForDelegate(s_hookCheckDeviceType));

                // Slot 14: GetDeviceCaps
                s_origGetDeviceCaps = (GetDeviceCaps_Delegate)Marshal.GetDelegateForFunctionPointer(
                    Marshal.ReadIntPtr(origVtable, 14 * IntPtr.Size), typeof(GetDeviceCaps_Delegate));
                s_hookGetDeviceCaps = new GetDeviceCaps_Delegate(Hook_GetDeviceCaps);
                Marshal.WriteIntPtr(s_newVtable, 14 * IntPtr.Size, Marshal.GetFunctionPointerForDelegate(s_hookGetDeviceCaps));

                // Slot 16: CreateDevice
                s_origCreateDevice = (CreateDevice_Delegate)Marshal.GetDelegateForFunctionPointer(
                    Marshal.ReadIntPtr(origVtable, 16 * IntPtr.Size), typeof(CreateDevice_Delegate));
                s_hookCreateDevice = new CreateDevice_Delegate(Hook_CreateDevice);
                Marshal.WriteIntPtr(s_newVtable, 16 * IntPtr.Size, Marshal.GetFunctionPointerForDelegate(s_hookCreateDevice));

                Log("D3D9 compatibility slots prepared.");
            }

            CaptureDisplayModes(pD3D, origVtable);
            // Preserve the native table and its driver-private entries. Only
            // replace the documented COM slots, never executable instructions.
            foreach (int slot in new int[] {6, 7, 9, 14, 16})
                ReplaceVtableSlot(origVtable, slot, Marshal.ReadIntPtr(s_newVtable, slot * IntPtr.Size));

            return pD3D;
        }
        catch (Exception ex)
        {
            Log("Exception in Direct3DCreate9_Hook: " + ex.ToString());
            return IntPtr.Zero;
        }
    }

    [DllImport("ws2_32.dll")]
    private static extern void WSASetLastError(int error);

    private static void InstallWinsockHooks()
    {
        if (s_winsockHooked) return;
        s_winsockHooked = true;
        IntPtr ws = LoadLibraryA("ws2_32.dll");
        s_connectExport = GetProcAddress(ws, "connect");
        s_wsaConnectExport = GetProcAddress(ws, "WSAConnect");
        s_origConnect = (Connect_Delegate)Marshal.GetDelegateForFunctionPointer(s_connectExport, typeof(Connect_Delegate));
        s_origWSAConnect = (WSAConnect_Delegate)Marshal.GetDelegateForFunctionPointer(s_wsaConnectExport, typeof(WSAConnect_Delegate));
        s_hookConnect = new Connect_Delegate(Hook_Connect);
        s_hookWSAConnect = new WSAConnect_Delegate(Hook_WSAConnect);
        InstallRendererEnumerationCompatibility();
        if (CanHotpatch(s_connectExport) && CanHotpatch(s_wsaConnectExport)) {
            // The documented x86 hotpatch entry is a two-byte NOP preceded by
            // five unused bytes. The original body starts at entry+2, so no
            // instruction is copied, split or relocated into a trampoline.
            s_origConnect = (Connect_Delegate)Marshal.GetDelegateForFunctionPointer(IntPtr.Add(s_connectExport, 2), typeof(Connect_Delegate));
            s_origWSAConnect = (WSAConnect_Delegate)Marshal.GetDelegateForFunctionPointer(IntPtr.Add(s_wsaConnectExport, 2), typeof(WSAConnect_Delegate));
            InstallHotpatch(s_connectExport, s_hookConnect);
            InstallHotpatch(s_wsaConnectExport, s_hookWSAConnect);
            Log("Winsock x86 hotpatch slots validated and routed.");
        } else { Log("Winsock hotpatch layout unavailable; using checked imports only."); }
        s_origGetProcAddress = (GetProcAddress_Delegate)Marshal.GetDelegateForFunctionPointer(
            GetProcAddress(LoadLibraryA("kernel32.dll"), "GetProcAddress"), typeof(GetProcAddress_Delegate));
        s_hookGetProcAddress = new GetProcAddress_Delegate(Hook_GetProcAddress);
        RefreshImports();
        // CShell is loaded later; apply only checked imports and build-specific compatibility.
        Thread worker = new Thread(delegate() {
            for (;;) {
                Thread.Sleep(100); RefreshImports();
            }
        });
        worker.IsBackground = true;
        worker.Start();
        Log("Known service routing initialized; game authentication code is unchanged.");
    }

    private static bool CanHotpatch(IntPtr entry)
    {
        byte[] bytes = ReadMemory(IntPtr.Add(entry, -5), 7);
        if (bytes == null || (entry.ToInt64() & 1) != 0 || bytes[5] != 0x8b || bytes[6] != 0xff) return false;
        for (int i = 0; i < 5; i++) if (bytes[i] != 0xcc && bytes[i] != 0x90) return false;
        return true;
    }

    private static void InstallRendererEnumerationCompatibility()
    {
        IntPtr library = LoadLibraryA("kernelbase.dll");
        IntPtr first = GetProcAddress(library, "FindFirstFileA");
        IntPtr next = GetProcAddress(library, "FindNextFileA");
        IntPtr close = GetProcAddress(library, "FindClose");
        IntPtr closeStub = GetProcAddress(LoadLibraryA("kernel32.dll"), "FindClose");
        byte[] stub = ReadMemory(closeStub, 6);
        if (!CanHotpatch(first) || !CanHotpatch(next) || stub == null || stub[0] != 0xff || stub[1] != 0x25) {
            Log("Renderer DLL enumeration compatibility unavailable on this Windows build."); return;
        }
        IntPtr closeSlot = new IntPtr(BitConverter.ToInt32(stub, 2));
        if (Marshal.ReadIntPtr(closeSlot) != close) { Log("Unexpected FindClose import; renderer enumeration unchanged."); return; }
        s_originalFindFirst = (FindFirstFile_Delegate)Marshal.GetDelegateForFunctionPointer(IntPtr.Add(first, 2), typeof(FindFirstFile_Delegate));
        s_originalFindNext = (FindNextFile_Delegate)Marshal.GetDelegateForFunctionPointer(IntPtr.Add(next, 2), typeof(FindNextFile_Delegate));
        s_originalFindClose = (FindClose_Delegate)Marshal.GetDelegateForFunctionPointer(close, typeof(FindClose_Delegate));
        s_hookFindFirst = new FindFirstFile_Delegate(Hook_FindFirstFile);
        s_hookFindNext = new FindNextFile_Delegate(Hook_FindNextFile);
        s_hookFindClose = delegate(IntPtr search) {
            lock (s_searchLock) s_compatDllSearches.Remove(search);
            return s_originalFindClose(search);
        };
        ReplaceVtableSlot(closeSlot, 0, Marshal.GetFunctionPointerForDelegate(s_hookFindClose));
        InstallHotpatch(first, s_hookFindFirst); InstallHotpatch(next, s_hookFindNext);
        Log("Renderer DLL enumeration compatibility installed; CShell bytes are unchanged.");
    }

    private static bool IsRootDllSearch(IntPtr pattern)
    {
        string value = Marshal.PtrToStringAnsi(pattern);
        if (string.IsNullOrEmpty(value)) return false;
        int separator = Math.Max(value.LastIndexOf('\\'), value.LastIndexOf('/'));
        string mask = value.Substring(separator + 1);
        if (!mask.Equals("*.dll", StringComparison.OrdinalIgnoreCase)) return false;
        string directory = separator < 0 ? Environment.CurrentDirectory : value.Substring(0, separator);
        return Path.GetFullPath(directory).TrimEnd('\\').Equals(AppDomain.CurrentDomain.BaseDirectory.TrimEnd('\\'), StringComparison.OrdinalIgnoreCase);
    }

    private static bool IsRendererEntry(IntPtr data)
    {
        return data != IntPtr.Zero && string.Equals(Marshal.PtrToStringAnsi(IntPtr.Add(data, 44)), "d3d9.dll", StringComparison.OrdinalIgnoreCase);
    }

    private static IntPtr Hook_FindFirstFile(IntPtr pattern, IntPtr data)
    {
        bool compatibilitySearch = IsRootDllSearch(pattern);
        IntPtr search = s_originalFindFirst(pattern, data);
        int error = Marshal.GetLastWin32Error();
        if (search == new IntPtr(-1)) { SetNativeLastError(error); return search; }
        lock (s_searchLock) {
            s_compatDllSearches.Remove(search);
            if (compatibilitySearch) s_compatDllSearches.Add(search);
        }
        if (compatibilitySearch) {
            while (IsRendererEntry(data)) {
                Log("Legacy root DLL scan: excluding this process's renderer compatibility module.");
                if (!s_originalFindNext(search, data)) {
                    error = Marshal.GetLastWin32Error();
                    lock (s_searchLock) s_compatDllSearches.Remove(search);
                    s_originalFindClose(search);
                    SetNativeLastError(error); return new IntPtr(-1);
                }
            }
        }
        return search;
    }

    private static bool Hook_FindNextFile(IntPtr search, IntPtr data)
    {
        bool compatibilitySearch;
        lock (s_searchLock) compatibilitySearch = s_compatDllSearches.Contains(search);
        bool found;
        do { found = s_originalFindNext(search, data); }
        while (found && compatibilitySearch && IsRendererEntry(data));
        int error = Marshal.GetLastWin32Error();
        if (!found) { lock (s_searchLock) s_compatDllSearches.Remove(search); }
        SetNativeLastError(error);
        return found;
    }

    private static void InstallHotpatch(IntPtr entry, Delegate replacement)
    {
        if (!CanHotpatch(entry)) throw new InvalidOperationException("Winsock hotpatch bytes changed.");
        IntPtr padding = IntPtr.Add(entry, -5);
        uint protection;
        if (!VirtualProtect(padding, (UIntPtr)7, 0x40, out protection))
            throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
        try {
            IntPtr target = Marshal.GetFunctionPointerForDelegate(replacement);
            Marshal.WriteInt32(padding, 1, unchecked((int)(target.ToInt64() - entry.ToInt64())));
            Marshal.WriteByte(padding, 0xe9);
            FlushInstructionCache(GetCurrentProcess(), padding, (UIntPtr)5);
            Marshal.WriteInt16(entry, unchecked((short)0xf9eb)); // JMP entry-5
            FlushInstructionCache(GetCurrentProcess(), entry, (UIntPtr)2);
        } finally { uint ignored; VirtualProtect(padding, (UIntPtr)7, protection, out ignored); }
    }

    private static void RefreshImports()
    {
        try
        {
            using (Process process = Process.GetCurrentProcess())
                foreach (ProcessModule module in process.Modules)
                {
                    // Only patch game modules; leave the CLR and Windows modules alone.
                    string path = module.FileName;
                    string root = AppDomain.CurrentDomain.BaseDirectory;
                    if (!path.StartsWith(root, StringComparison.OrdinalIgnoreCase) &&
                        !module.ModuleName.Equals("CShell.dll", StringComparison.OrdinalIgnoreCase)) continue;
                    if (module.ModuleName.Equals("CShell.dll", StringComparison.OrdinalIgnoreCase))
                        ExpandCharacterSlotPool(module.BaseAddress);
                    try { ReplaceImport(module, "connect", s_hookConnect); ReplaceImport(module, "WSAConnect", s_hookWSAConnect); ReplaceImport(module, "GetProcAddress", s_hookGetProcAddress); }
                    catch (Exception ex) { Log("Import routing skipped: " + module.ModuleName + " " + ex.Message); }
                }
        }
        catch (Exception ex) { Log("Import enumeration: " + ex.Message); }
        // This engine can manually map CShell, omitting it from Process.Modules.
        // The full instruction signature below validates the observed 2012 base.
        ExpandCharacterSlotPool(new IntPtr(0x34000000));
    }

    private static void ExpandCharacterSlotPool(IntPtr image)
    {
        if (s_characterSlotsChecked) return;
        // September 2012 CharInfo builds the available-key set with keys 1..10.
        // With a large owned-character inventory those keys are exhausted and
        // CHARSAVE/DEFAULTCHAR return before sending the newly selected cards.
        // The containers and wire keys are dynamic/u8; 64 records also fit the
        // native 0x2000-byte save buffer (64 * 69 + 1).
        byte[] expected = new byte[] {
            0x33,0xc0,0x8d,0x49,0x00,0x8d,0x55,0xe8,0x8d,0x58,0x01,0x52,
            0x8d,0x4d,0x8c,0x89,0x5d,0xe8,0xe8,0xde,0xcd,0xfe,0xff,0x8b,
            0xc3,0x83,0xf8,0x0a,0x7c,0xe7
        };
        IntPtr start = IntPtr.Add(image, 0x1cf26b);
        byte[] actual = new byte[expected.Length];
        UIntPtr read;
        if (!ReadProcessMemory(GetCurrentProcess(), start, actual, (UIntPtr)actual.Length, out read) ||
            read.ToUInt64() != (ulong)actual.Length) return;
        for (int i = 0; i < expected.Length; i++) {
            if (actual[i] != expected[i] && !(i == 27 && actual[i] == 64)) {
                // The manually mapped image may still be unpacking. Never write
                // mismatched code; retry when its complete signature is ready.
                return;
            }
        }
        IntPtr limit = IntPtr.Add(start, 27);
        uint protection;
        if (!VirtualProtect(limit, (UIntPtr)1, 0x40, out protection)) return;
        try {
            Marshal.WriteByte(limit, 64);
            FlushInstructionCache(GetCurrentProcess(), limit, (UIntPtr)1);
            s_characterSlotsChecked = true;
            Log("2012 character key pool expanded to 64; selection and save handlers unchanged.");
        } finally {
            uint ignored;
            VirtualProtect(limit, (UIntPtr)1, protection, out ignored);
        }
    }

    private static void ReplaceImport(ProcessModule module, string symbol, Delegate replacement)
    {
        ReplaceImport(module.BaseAddress, module.ModuleMemorySize, module.ModuleName, symbol, replacement);
    }

    private static void ReplaceImport(IntPtr image, int size, string moduleName, string symbol, Delegate replacement)
    {
        if (size < 256 || Marshal.ReadInt16(image) != 0x5a4d) return;
        int pe = Marshal.ReadInt32(image, 0x3c);
        if (pe < 0 || pe > size - 256 || Marshal.ReadInt32(image, pe) != 0x4550 ||
            Marshal.ReadInt16(image, pe + 24) != 0x10b) return;
        int imports = Marshal.ReadInt32(image, pe + 128);
        int importSize = Marshal.ReadInt32(image, pe + 132);
        if (imports <= 0 || importSize < 20 || imports > size - importSize) return;
        IntPtr hook = Marshal.GetFunctionPointerForDelegate(replacement);
        for (int d = imports; d <= imports + importSize - 20; d += 20)
        {
            int dll = Marshal.ReadInt32(image, d + 12);
            int slots = Marshal.ReadInt32(image, d + 16);
            if (dll == 0) break;
            if (dll < 0 || dll > size - 32 || slots <= 0) continue;
            string library = Marshal.PtrToStringAnsi(IntPtr.Add(image, dll));
            bool resolver = symbol == "GetProcAddress";
            if (resolver ? (!library.Equals("kernel32.dll", StringComparison.OrdinalIgnoreCase) && !library.Equals("kernelbase.dll", StringComparison.OrdinalIgnoreCase)) :
                (!library.Equals("ws2_32.dll", StringComparison.OrdinalIgnoreCase) && !library.Equals("wsock32.dll", StringComparison.OrdinalIgnoreCase))) continue;
            IntPtr expected = GetProcAddress(LoadLibraryA(library), symbol);
            if (expected == IntPtr.Zero) continue;
            // Packed/ordinal imports may lack OriginalFirstThunk. Match the
            // resolved export address in the real IAT instead of name records.
            for (int i = 0; slots + i * 4 <= size - 4; i++)
            {
                IntPtr slot = IntPtr.Add(image, slots + i * 4);
                IntPtr value = Marshal.ReadIntPtr(slot);
                if (value == IntPtr.Zero) break;
                if (value == hook) continue;
                if (value != expected) continue;
                uint previous;
                if (!VirtualProtect(slot, (UIntPtr)4, 0x04, out previous)) continue;
                try { Marshal.WriteIntPtr(slot, hook); }
                finally { uint ignored; VirtualProtect(slot, (UIntPtr)4, previous, out ignored); }
                Log("Service import routed: " + moduleName + "!" + symbol);
            }
        }
    }

    private static byte[] ReadMemory(IntPtr address, int size)
    {
        byte[] bytes = new byte[size];
        UIntPtr read;
        return ReadProcessMemory(GetCurrentProcess(), address, bytes, (UIntPtr)size, out read) && read.ToUInt64() == (ulong)size ? bytes : null;
    }

    private static IntPtr Hook_GetProcAddress(IntPtr module, IntPtr name)
    {
        IntPtr result = s_origGetProcAddress(module, name);
        if (result == s_connectExport) {
            Log("Dynamic connect export routed.");
            return Marshal.GetFunctionPointerForDelegate(s_hookConnect);
        }
        if (result == s_wsaConnectExport) {
            Log("Dynamic WSAConnect export routed.");
            return Marshal.GetFunctionPointerForDelegate(s_hookWSAConnect);
        }
        return result;
    }

    private static bool LocalSockAddr(IntPtr name, int namelen, out IntPtr copy)
    {
        copy = IntPtr.Zero;
        if (name == IntPtr.Zero || namelen < 16 || Marshal.ReadInt16(name) != 2) return false;
        int port = (Marshal.ReadByte(name, 2) << 8) | Marshal.ReadByte(name, 3);
        byte[] ip = new byte[4];
        Marshal.Copy(IntPtr.Add(name, 4), ip, 0, 4);
        string address = new IPAddress(ip).ToString();
        bool mapped = (address == "212.68.41.11" && port == 30706) ||
                      (address == "61.74.68.183" && port == 10300);
        if (!mapped && address != s_targetServerIp && ip[0] != 127)
        {
            Log("Rejected unknown service: " + address + ":" + port);
            return false;
        }
        if (mapped)
        {
            byte[] bytes = new byte[namelen];
            Marshal.Copy(name, bytes, 0, namelen);
            Array.Copy(s_targetIpBytes, 0, bytes, 4, 4);
            copy = Marshal.AllocHGlobal(namelen);
            Marshal.Copy(bytes, 0, copy, namelen);
            Log("Mapped service: " + address + ":" + port + " -> " + s_targetServerIp);
        }
        return true;
    }

    private static int Hook_Connect(IntPtr socket, IntPtr name, int length)
    {
        ExpandCharacterSlotPool(new IntPtr(0x34000000));
        IntPtr copy;
        if (!LocalSockAddr(name, length, out copy)) { WSASetLastError(10013); return -1; }
        try { return s_origConnect(socket, copy == IntPtr.Zero ? name : copy, length); }
        finally { if (copy != IntPtr.Zero) Marshal.FreeHGlobal(copy); }
    }

    private static int Hook_WSAConnect(IntPtr socket, IntPtr name, int length, IntPtr a, IntPtr b, IntPtr c, IntPtr d)
    {
        ExpandCharacterSlotPool(new IntPtr(0x34000000));
        IntPtr copy;
        if (!LocalSockAddr(name, length, out copy)) { WSASetLastError(10013); return -1; }
        try { return s_origWSAConnect(socket, copy == IntPtr.Zero ? name : copy, length, a, b, c, d); }
        finally { if (copy != IntPtr.Zero) Marshal.FreeHGlobal(copy); }
    }

    private static int Hook_GetDeviceCaps(IntPtr thisPtr, uint adapter, uint devType, IntPtr pCaps)
    {
        int hr = s_origGetDeviceCaps(thisPtr, adapter, devType, pCaps);
        if (hr != 0)
        {
            hr = s_origGetDeviceCaps(thisPtr, adapter, 2, pCaps);
        }

        if (hr == 0 && pCaps != IntPtr.Zero)
        {
            int devCaps = Marshal.ReadInt32(pCaps, 0x1c);
            devCaps |= 0x10000; // D3DDEVCAPS_HWTRANSFORMANDLIGHT
            Marshal.WriteInt32(pCaps, 0x1c, devCaps);
        }
        return hr;
    }

    private static int Hook_CheckDeviceType(IntPtr thisPtr, uint adapter, uint devType, uint adapterFormat, uint backBufferFormat, bool windowed)
    {
        int hr = s_origCheckDeviceType(thisPtr, adapter, devType, adapterFormat, backBufferFormat, windowed);
        if (hr != 0)
        {
            return s_origCheckDeviceType(thisPtr, adapter, devType, 22, 22, windowed);
        }
        return hr;
    }

    private static uint Hook_GetAdapterModeCount(IntPtr thisPtr, uint adapter, uint format)
    {
        return (uint)DisplayModes(adapter, format).Length;
    }

    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate uint GetAdapterCount_Delegate(IntPtr self);

    private static void CaptureDisplayModes(IntPtr d3d, IntPtr nativeTable)
    {
        // Enumerate while this object still has its native table. The native
        // D3D9Ex enumeration path faults when called on the cloned table.
        GetAdapterCount_Delegate count = (GetAdapterCount_Delegate)Marshal.GetDelegateForFunctionPointer(
            Marshal.ReadIntPtr(nativeTable, 4 * IntPtr.Size), typeof(GetAdapterCount_Delegate));
        IntPtr mode = Marshal.AllocHGlobal(16);
        try {
            for (uint adapter = 0; adapter < count(d3d); adapter++) {
                foreach (uint format in new uint[] {22, 21, 31, 24, 25, 23}) {
                    List<byte[]> modes = new List<byte[]>();
                    uint total = s_origGetAdapterModeCount(d3d, adapter, format);
                    for (uint index = 0; index < total; index++) {
                        if (s_origEnumAdapterModes(d3d, adapter, format, index, mode) != 0) continue;
                        byte[] bytes = new byte[16];
                        Marshal.Copy(mode, bytes, 0, bytes.Length);
                        modes.Add(bytes);
                    }
                    s_displayModes[((ulong)adapter << 32) | format] = modes.ToArray();
                }
            }
        } finally { Marshal.FreeHGlobal(mode); }
    }

    private static byte[][] DisplayModes(uint adapter, uint format)
    {
        byte[][] modes;
        if (s_displayModes.TryGetValue(((ulong)adapter << 32) | format, out modes) && modes.Length > 0) return modes;
        return s_displayModes.TryGetValue(((ulong)adapter << 32) | 22, out modes) ? modes : new byte[0][];
    }

    private static int Hook_EnumAdapterModes(IntPtr thisPtr, uint adapter, uint format, uint modeIndex, IntPtr pMode)
    {
        byte[][] modes = DisplayModes(adapter, format);
        if (pMode == IntPtr.Zero || modeIndex >= modes.Length) return unchecked((int)0x8876086c);
        Marshal.Copy(modes[modeIndex], 0, pMode, 16);
        Marshal.WriteInt32(pMode, 12, (int)format);
        return 0;
    }

    private static int Hook_CreateTexture(IntPtr dev, uint w, uint h, uint levels, uint usage, uint format, uint pool, out IntPtr ppTex, IntPtr pShared)
    {
        if (pool == 1) // D3DPOOL_MANAGED
        {
            pool = 0; // D3DPOOL_DEFAULT
            usage |= 0x200; // D3DUSAGE_DYNAMIC
        }
        return s_origCreateTexture(dev, w, h, levels, usage, format, pool, out ppTex, pShared);
    }

    private static int Hook_CreateVertexBuffer(IntPtr dev, uint length, uint usage, uint fvf, uint pool, out IntPtr ppVB, IntPtr pShared)
    {
        if (pool == 1)
        {
            pool = 0;
            usage |= 0x200;
        }
        return s_origCreateVertexBuffer(dev, length, usage, fvf, pool, out ppVB, pShared);
    }

    private static int Hook_CreateIndexBuffer(IntPtr dev, uint length, uint usage, uint format, uint pool, out IntPtr ppIB, IntPtr pShared)
    {
        if (pool == 1)
        {
            pool = 0;
            usage |= 0x200;
        }
        return s_origCreateIndexBuffer(dev, length, usage, format, pool, out ppIB, pShared);
    }

    private static int Hook_CreateDevice(IntPtr thisPtr, uint adapter, uint devType, IntPtr hFocusWindow, uint behaviorFlags, IntPtr pPresentationParameters, IntPtr ppReturnedDeviceInterface)
    {
        Log(string.Format("Hook_CreateDevice called: adapter={0}, devType={1}, flags={2:X}", adapter, devType, behaviorFlags));
        int hr = s_origCreateDevice(thisPtr, adapter, devType, hFocusWindow, behaviorFlags, pPresentationParameters, ppReturnedDeviceInterface);
        Log("Hook_CreateDevice orig returned: " + hr.ToString("X"));

        if (hr != 0 && pPresentationParameters != IntPtr.Zero)
        {
            int bbFormat = Marshal.ReadInt32(pPresentationParameters, 8);
            Log(string.Format("Hook_CreateDevice failed with BackBufferFormat={0}. Retrying with D3DFMT_X8R8G8B8 (22)", bbFormat));
            Marshal.WriteInt32(pPresentationParameters, 8, 22);
            hr = s_origCreateDevice(thisPtr, adapter, devType, hFocusWindow, behaviorFlags, pPresentationParameters, ppReturnedDeviceInterface);
            Log("Hook_CreateDevice retry with 22 returned: " + hr.ToString("X"));

            if (hr != 0 && (behaviorFlags & 0x40) != 0)
            {
                uint swFlags = (behaviorFlags & ~0x40u) | 0x20u;
                hr = s_origCreateDevice(thisPtr, adapter, devType, hFocusWindow, swFlags, pPresentationParameters, ppReturnedDeviceInterface);
                Log("Hook_CreateDevice retry with SW returned: " + hr.ToString("X"));
            }
        }

        if (hr == 0 && ppReturnedDeviceInterface != IntPtr.Zero && !s_deviceHooksInstalled)
        {
            s_deviceHooksInstalled = true;
            try
            {
                IntPtr pDev = Marshal.ReadIntPtr(ppReturnedDeviceInterface);
                if (pDev != IntPtr.Zero)
                {
                    IntPtr devVtable = Marshal.ReadIntPtr(pDev);

                    s_origCreateTexture = (CreateTexture_Delegate)Marshal.GetDelegateForFunctionPointer(Marshal.ReadIntPtr(devVtable, 23 * IntPtr.Size), typeof(CreateTexture_Delegate));
                    s_origCreateVertexBuffer = (CreateVertexBuffer_Delegate)Marshal.GetDelegateForFunctionPointer(Marshal.ReadIntPtr(devVtable, 26 * IntPtr.Size), typeof(CreateVertexBuffer_Delegate));
                    s_origCreateIndexBuffer = (CreateIndexBuffer_Delegate)Marshal.GetDelegateForFunctionPointer(Marshal.ReadIntPtr(devVtable, 27 * IntPtr.Size), typeof(CreateIndexBuffer_Delegate));
                    s_hookCreateTexture = new CreateTexture_Delegate(Hook_CreateTexture);
                    s_hookCreateVertexBuffer = new CreateVertexBuffer_Delegate(Hook_CreateVertexBuffer);
                    s_hookCreateIndexBuffer = new CreateIndexBuffer_Delegate(Hook_CreateIndexBuffer);
                    ReplaceVtableSlot(devVtable, 23, Marshal.GetFunctionPointerForDelegate(s_hookCreateTexture));
                    ReplaceVtableSlot(devVtable, 26, Marshal.GetFunctionPointerForDelegate(s_hookCreateVertexBuffer));
                    ReplaceVtableSlot(devVtable, 27, Marshal.GetFunctionPointerForDelegate(s_hookCreateIndexBuffer));
                    Log("Device resource compatibility slots installed; native table preserved.");
                }
            }
            catch (Exception ex)
            {
                Log("Exception installing device inline hooks: " + ex.ToString());
            }
        }

        return hr;
    }

    private static void ReplaceVtableSlot(IntPtr table, int index, IntPtr function)
    {
        IntPtr slot = IntPtr.Add(table, index * IntPtr.Size);
        uint protection;
        if (!VirtualProtect(slot, (UIntPtr)IntPtr.Size, 0x04, out protection))
            throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());
        try { Marshal.WriteIntPtr(slot, function); }
        finally { uint ignored; VirtualProtect(slot, (UIntPtr)IntPtr.Size, protection, out ignored); }
    }

}
