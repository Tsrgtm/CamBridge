param([string]$IconPath,[string]$OutputPath)
$ErrorActionPreference='Stop'
# Emit Win32 ICON and GROUP_ICON resources directly, avoiding an extra SDK dependency.
# Format: https://learn.microsoft.com/windows/win32/menurc/resource-file-formats
$icon=[IO.File]::ReadAllBytes($IconPath)
$count=[BitConverter]::ToUInt16($icon,4)
$stream=[IO.File]::Create($OutputPath)
$writer=[IO.BinaryWriter]::new($stream)
function Write-Resource([uint16]$Type,[uint16]$Name,[byte[]]$Data){
    $writer.Write([uint32]$Data.Length);$writer.Write([uint32]32)
    $writer.Write([uint16]65535);$writer.Write($Type)
    $writer.Write([uint16]65535);$writer.Write($Name)
    $writer.Write([uint32]0);$writer.Write([uint16]0x1030);$writer.Write([uint16]0)
    $writer.Write([uint32]0);$writer.Write([uint32]0);$writer.Write($Data)
    while($stream.Position % 4){$writer.Write([byte]0)}
}
try {
    Write-Resource 0 0 ([byte[]]@())
    $groupStream=[IO.MemoryStream]::new()
    $group=[IO.BinaryWriter]::new($groupStream)
    $group.Write([uint16]0);$group.Write([uint16]1);$group.Write([uint16]$count)
    for($i=0;$i -lt $count;$i++){
        $entry=6+16*$i
        $length=[BitConverter]::ToUInt32($icon,$entry+8)
        $offset=[BitConverter]::ToUInt32($icon,$entry+12)
        $data=[byte[]]::new($length)
        [Array]::Copy($icon,$offset,$data,0,$length)
        Write-Resource 3 ($i+1) $data
        $group.Write($icon,$entry,12);$group.Write([uint16]($i+1))
    }
    Write-Resource 14 1 ($groupStream.ToArray())
    $group.Dispose()
} finally {$writer.Dispose()}