.corflags 0x00000002 // 32-bit required
.assembly extern mscorlib {}
.assembly d3d9 {}
.module d3d9.dll
.file D3DHookFull.netmodule
.module extern D3DHookFull.netmodule

.vtfixup [1] int32 fromunmanaged at VT_01
.data VT_01 = int32(0)

.class public abstract auto ansi beforefieldinit D3D9Wrapper
{
    .method public static native int modopt([mscorlib]System.Runtime.CompilerServices.CallConvStdcall) 
        Direct3DCreate9(uint32 SDKVersion) cil managed
    {
        .vtentry 1:1
        .export [1] as Direct3DCreate9
        .maxstack 2
        ldarg.0
        call native int [.module D3DHookFull.netmodule]D3DHook::Direct3DCreate9_Hook(uint32)
        ret
    }
}
