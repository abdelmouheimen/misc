# Affiche la classe d'efficacité de chaque processeur logique (Windows, processeurs hybrides).
# Sur Intel hybride, la classe la plus élevée correspond aux cœurs P (performance).
# Sortie : une ligne JSON {"logical":[...],"pcoreMask":"0x..","pcores":[...]} pour les scripts.
$src = @'
using System;
using System.Runtime.InteropServices;
using System.Collections.Generic;
public static class CpuSets {
  [DllImport("kernel32.dll", SetLastError = true)]
  static extern bool GetSystemCpuSetInformation(IntPtr info, uint len, out uint ret, IntPtr proc, uint flags);
  public static List<int[]> Read() {
    uint len; GetSystemCpuSetInformation(IntPtr.Zero, 0, out len, IntPtr.Zero, 0);
    IntPtr buf = Marshal.AllocHGlobal((int)len);
    var res = new List<int[]>();
    try {
      if (!GetSystemCpuSetInformation(buf, len, out len, IntPtr.Zero, 0)) throw new Exception("GetSystemCpuSetInformation");
      int off = 0;
      while (off < len) {
        int size = Marshal.ReadInt32(buf, off);
        // SYSTEM_CPU_SET_INFORMATION.CpuSet : Id(4) Group(2) LogicalProcessorIndex(1) CoreIndex(1)
        // LastLevelCacheIndex(1) NumaNodeIndex(1) EfficiencyClass(1)
        int lp = Marshal.ReadByte(buf, off + 8 + 6);
        int core = Marshal.ReadByte(buf, off + 8 + 7);
        int eff = Marshal.ReadByte(buf, off + 8 + 10);
        res.Add(new int[] { lp, core, eff });
        off += size;
      }
    } finally { Marshal.FreeHGlobal(buf); }
    return res;
  }
}
'@
Add-Type -TypeDefinition $src
$rows = [CpuSets]::Read() | ForEach-Object { [pscustomobject]@{ logical = $_[0]; core = $_[1]; efficiencyClass = $_[2] } }
$max = ($rows | Measure-Object efficiencyClass -Maximum).Maximum
$p = @($rows | Where-Object { $_.efficiencyClass -eq $max } | ForEach-Object { $_.logical })
$mask = 0L; foreach ($i in $p) { $mask = $mask -bor (1L -shl $i) }
[pscustomobject]@{ logical = $rows; pcores = $p; pcoreMask = ('0x{0:X}' -f $mask) } | ConvertTo-Json -Compress -Depth 4
