package me.dartcv.minix.control

/**
 * Static identity of the recovered 1.58.2 arm64 game module.
 *
 * Runtime profile selection is pinned by the full-file SHA-256. The GNU build
 * ID, file size, and ELF layout values are retained as bounded diagnostics and
 * evidence metadata; mappedBytes is a process-mapping measurement and must not
 * be compared with [FILE_SIZE_BYTES].
 */
internal object ControlGameAppArtifact1582 {
    const val MODULE_NAME = "liblibGameApp.so"
    const val SHA256 =
        "d8efe55c37b061ce41cb3830ec743401138bf40b8bacf251f18f7b026bf0140e"
    const val GNU_BUILD_ID = "662a450a7331aff319cbc4b3897c2124f8fd61f9"
    const val FILE_SIZE_BYTES = 176_533_320L

    const val ELF_CLASS = "ELF64"
    const val ELF_MACHINE = "AArch64"
    const val ELF_TYPE = "DYN"
    const val ELF_ENTRY_POINT = 0x02c49f50L
    const val LOAD_SEGMENT_COUNT = 3
    const val LOAD_ALIGNMENT_BYTES = 0x1000L
    const val LOAD_FILE_BACKED_END = 0x0a85a780L
    const val LOAD_VIRTUAL_END = 0x0b3c0128L

    // The final RW PT_LOAD has a virtual/file delta of 0x2000. Its file-backed
    // bytes end exactly where .bss begins; the dedicated anonymous mapping
    // starts at the next 4 KiB page. These are load-bias-relative addresses.
    const val BSS_SECTION_VADDR = 0x0a85c780L
    const val ANON_BSS_4K_OFFSET = 0x0a85d000L
    const val ANON_BSS_4K_END = 0x0b3c1000L
    const val KILL_COUNT_ANCHOR_OFFSET = 0x0001cc78L
    const val KILL_COUNT_LOAD_OFFSET = ANON_BSS_4K_OFFSET + KILL_COUNT_ANCHOR_OFFSET

    // IPlayerControl* global recovered from World::GetPlayerPosition.
    const val PLAYER_CONTROL_GLOBAL_RVA = 0x0afde370L
    const val PLAYER_CONTROL_BSS_OFFSET = PLAYER_CONTROL_GLOBAL_RVA - ANON_BSS_4K_OFFSET

    // Keep the packaged profile reproducible without embedding a workstation path.
    const val SOURCE_ARTIFACT = "artifacts/liblibGameApp-1.58.2-elf-identity.json"
    const val SOURCE_REFERENCE =
        "Full-file SHA-256, GNU Build ID, size, ELF64/AArch64 type, and PT_LOAD layout verified statically"
}
