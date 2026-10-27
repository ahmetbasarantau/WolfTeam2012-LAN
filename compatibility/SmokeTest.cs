using System;
using System.Net;
using System.Runtime.InteropServices;
using System.Collections.Generic;
using System.IO;

public static class SmokeTest
{
    [DllImport("kernel32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    private static extern IntPtr LoadLibraryW(string path);
    [DllImport("kernel32.dll", CharSet=CharSet.Ansi)]
    private static extern IntPtr GetProcAddress(IntPtr module, string symbol);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate IntPtr Create(uint version);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate uint Release(IntPtr self);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate uint AdapterCount(IntPtr self);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate uint ModeCount(IntPtr self, uint adapter, uint format);
    [UnmanagedFunctionPointer(CallingConvention.StdCall)]
    private delegate int EnumModes(IntPtr self, uint adapter, uint format, uint index, IntPtr mode);
    [DllImport("kernel32.dll", CharSet=CharSet.Ansi, SetLastError=true)]
    private static extern IntPtr FindFirstFileA(string pattern, IntPtr data);
    [DllImport("kernel32.dll", SetLastError=true)]
    private static extern bool FindNextFileA(IntPtr search, IntPtr data);
    [DllImport("kernel32.dll", SetLastError=true)]
    private static extern bool FindClose(IntPtr search);

    private static List<string> Files(string directory)
    {
        List<string> names = new List<string>();
        IntPtr data = Marshal.AllocHGlobal(320);
        IntPtr search = new IntPtr(-1);
        try {
            search = FindFirstFileA(Path.Combine(directory, "*.dll"), data);
            if (search == new IntPtr(-1)) return names;
            do { names.Add(Marshal.PtrToStringAnsi(IntPtr.Add(data, 44)).ToLowerInvariant()); }
            while (FindNextFileA(search, data));
            if (Marshal.GetLastWin32Error() != 18) throw new Exception("DLL enumeration returned an unexpected error");
        } finally {
            if (search != new IntPtr(-1) && !FindClose(search)) throw new Exception("FindClose failed");
            Marshal.FreeHGlobal(data);
        }
        return names;
    }

    public static int Main(string[] args)
    {
        IntPtr module = LoadLibraryW(args[0]);
        if (module == IntPtr.Zero) throw new Exception("Proxy load failed: " + Marshal.GetLastWin32Error());
        Create create = (Create)Marshal.GetDelegateForFunctionPointer(GetProcAddress(module, "Direct3DCreate9"), typeof(Create));
        IntPtr d3d = create(32);
        if (d3d == IntPtr.Zero) throw new Exception("Direct3DCreate9 returned null");
        IntPtr table = Marshal.ReadIntPtr(d3d);
        AdapterCount adapters = (AdapterCount)Marshal.GetDelegateForFunctionPointer(Marshal.ReadIntPtr(table, 4 * IntPtr.Size), typeof(AdapterCount));
        uint count = adapters(d3d);
        if (count == 0) throw new Exception("No display adapters");
        Delegate modes = Marshal.GetDelegateForFunctionPointer(Marshal.ReadIntPtr(table, 6 * IntPtr.Size), typeof(ModeCount));
        Delegate enumerate = Marshal.GetDelegateForFunctionPointer(Marshal.ReadIntPtr(table, 7 * IntPtr.Size), typeof(EnumModes));
        IntPtr mode = Marshal.AllocHGlobal(16);
        try {
            foreach (uint format in new uint[] {22, 21, 31, 24, 25, 23}) {
                uint total = (uint)modes.DynamicInvoke(d3d, (uint)0, format);
                for (uint i = 0; i < total; i++) {
                    if ((int)enumerate.DynamicInvoke(d3d, (uint)0, format, i, mode) != 0) throw new Exception("Display mode enumeration failed");
                }
            }
        } finally { Marshal.FreeHGlobal(mode); }
        Release release = (Release)Marshal.GetDelegateForFunctionPointer(Marshal.ReadIntPtr(table, 2 * IntPtr.Size), typeof(Release));
        release(d3d);
        string root = AppDomain.CurrentDomain.BaseDirectory;
        if (Files(root).Contains("d3d9.dll")) throw new Exception("Renderer DLL was included in legacy root scan");
        string fixture = Path.Combine(root, "enumeration-fixture");
        Directory.CreateDirectory(fixture);
        File.WriteAllText(Path.Combine(fixture, "d3d9.dll"), "fixture");
        File.WriteAllText(Path.Combine(fixture, "keep.dll"), "fixture");
        List<string> outside = Files(fixture);
        if (!outside.Contains("d3d9.dll") || !outside.Contains("keep.dll")) throw new Exception("Unrelated directory enumeration was changed");
        Console.WriteLine("Legacy DLL enumeration excludes renderer only in application root; other paths and FindClose passed.");
        Console.WriteLine("Proxy export, D3D9 creation, adapter enumeration and release passed (" + count + " adapters).");
        return 0;
    }
}
