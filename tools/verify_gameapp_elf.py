#!/usr/bin/env python3
"""Reproducibly verify the pinned GameApp ELF identity without a device."""

from __future__ import annotations

import argparse
import hashlib
import json
import struct
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


PT_LOAD = 1
PT_DYNAMIC = 2
PT_NOTE = 4
DT_NULL = 0
DT_STRTAB = 5
DT_STRSZ = 10
DT_SONAME = 14
NT_GNU_BUILD_ID = 3
MAX_PROGRAM_HEADERS = 128
MAX_SECTION_HEADERS = 16_384
MAX_NOTE_BYTES = 1024 * 1024
MAX_STRING_TABLE_BYTES = 64 * 1024 * 1024


@dataclass(frozen=True)
class ProgramHeader:
    type: int
    flags: int
    offset: int
    virtual_address: int
    file_size: int
    memory_size: int
    alignment: int


def checked_slice(data: bytes, offset: int, size: int, label: str) -> bytes:
    if offset < 0 or size < 0 or offset + size > len(data):
        raise ValueError(f"{label} is outside the file")
    return data[offset : offset + size]


def align_up(value: int, alignment: int) -> int:
    if alignment <= 0 or alignment & (alignment - 1):
        raise ValueError("alignment must be a positive power of two")
    return (value + alignment - 1) & -alignment


def hex_value(value: int) -> str:
    return f"0x{value:08x}"


def parse_expected_int(value: Any) -> int:
    if isinstance(value, int):
        return value
    if isinstance(value, str):
        return int(value, 0)
    raise TypeError(f"unsupported integer value: {value!r}")


def flag_text(flags: int) -> str:
    return "".join(("R" if flags & 4 else "-", "W" if flags & 2 else "-", "X" if flags & 1 else "-"))


def read_c_string(data: bytes, offset: int, label: str) -> str:
    if offset < 0 or offset >= len(data):
        raise ValueError(f"{label} offset is outside its string table")
    end = data.find(b"\0", offset)
    if end < 0:
        raise ValueError(f"{label} is not NUL terminated")
    return data[offset:end].decode("utf-8", errors="strict")


def vaddr_to_file_offset(headers: list[ProgramHeader], virtual_address: int) -> int:
    matches = [
        header
        for header in headers
        if header.type == PT_LOAD
        and header.virtual_address <= virtual_address < header.virtual_address + header.file_size
    ]
    if len(matches) != 1:
        raise ValueError("virtual address does not map to exactly one file-backed PT_LOAD")
    header = matches[0]
    return header.offset + virtual_address - header.virtual_address


def parse_notes(blob: bytes) -> list[tuple[bytes, int, bytes]]:
    notes: list[tuple[bytes, int, bytes]] = []
    cursor = 0
    while cursor + 12 <= len(blob):
        name_size, description_size, note_type = struct.unpack_from("<III", blob, cursor)
        cursor += 12
        name_end = cursor + name_size
        if name_end > len(blob):
            raise ValueError("ELF note name exceeds PT_NOTE")
        name = blob[cursor:name_end].rstrip(b"\0")
        cursor = align_up(name_end, 4)
        description_end = cursor + description_size
        if description_end > len(blob):
            raise ValueError("ELF note description exceeds PT_NOTE")
        description = blob[cursor:description_end]
        cursor = align_up(description_end, 4)
        notes.append((name, note_type, description))
    if any(blob[cursor:]):
        raise ValueError("PT_NOTE has non-zero trailing bytes")
    return notes


def parse_elf(path: Path) -> dict[str, Any]:
    data = path.read_bytes()
    if len(data) < 64 or data[:4] != b"\x7fELF":
        raise ValueError("input is not an ELF file")
    if data[4] != 2 or data[5] != 1 or data[6] != 1:
        raise ValueError("only ELF64 little-endian version 1 is accepted")

    fields = struct.unpack_from("<HHIQQQIHHHHHH", data, 16)
    (
        elf_type,
        machine,
        version,
        entry_point,
        program_header_offset,
        section_header_offset,
        _flags,
        elf_header_size,
        program_header_size,
        program_header_count,
        section_header_size,
        section_header_count,
        section_name_index,
    ) = fields
    if version != 1 or elf_header_size != 64 or program_header_size != 56:
        raise ValueError("ELF header layout is invalid")
    if program_header_count not in range(1, MAX_PROGRAM_HEADERS + 1):
        raise ValueError("ELF program-header count is outside the verifier bound")

    headers: list[ProgramHeader] = []
    for index in range(program_header_count):
        offset = program_header_offset + index * program_header_size
        row = checked_slice(data, offset, program_header_size, f"program header {index}")
        p_type, p_flags, p_offset, p_vaddr, _paddr, p_filesz, p_memsz, p_align = struct.unpack(
            "<IIQQQQQQ", row
        )
        if p_filesz > p_memsz or p_offset + p_filesz > len(data):
            raise ValueError(f"program header {index} has an invalid file range")
        if p_align > 1 and (p_align & (p_align - 1) or p_offset % p_align != p_vaddr % p_align):
            raise ValueError(f"program header {index} has invalid alignment")
        headers.append(
            ProgramHeader(p_type, p_flags, p_offset, p_vaddr, p_filesz, p_memsz, p_align)
        )

    load_headers = [header for header in headers if header.type == PT_LOAD]
    if not load_headers:
        raise ValueError("ELF has no PT_LOAD")

    build_ids: set[str] = set()
    for index, header in enumerate(headers):
        if header.type != PT_NOTE:
            continue
        if header.file_size > MAX_NOTE_BYTES:
            raise ValueError(f"PT_NOTE {index} exceeds the verifier bound")
        blob = checked_slice(data, header.offset, header.file_size, f"PT_NOTE {index}")
        for name, note_type, description in parse_notes(blob):
            if name == b"GNU" and note_type == NT_GNU_BUILD_ID and description:
                build_ids.add(description.hex())
    if len(build_ids) != 1:
        raise ValueError("ELF must contain exactly one GNU Build-ID")

    dynamic_headers = [header for header in headers if header.type == PT_DYNAMIC]
    if len(dynamic_headers) != 1:
        raise ValueError("ELF must contain exactly one PT_DYNAMIC")
    dynamic = dynamic_headers[0]
    if dynamic.file_size % 16:
        raise ValueError("PT_DYNAMIC size is not aligned to Elf64_Dyn")
    dynamic_values: dict[int, list[int]] = {}
    for cursor in range(dynamic.offset, dynamic.offset + dynamic.file_size, 16):
        tag, value = struct.unpack_from("<qQ", data, cursor)
        if tag == DT_NULL:
            break
        dynamic_values.setdefault(tag, []).append(value)
    string_table_vaddr = dynamic_values.get(DT_STRTAB, [])
    string_table_size = dynamic_values.get(DT_STRSZ, [])
    soname_offsets = dynamic_values.get(DT_SONAME, [])
    if len(string_table_vaddr) != 1 or len(string_table_size) != 1 or len(soname_offsets) != 1:
        raise ValueError("ELF dynamic string/SO name metadata is ambiguous")
    if string_table_size[0] not in range(1, MAX_STRING_TABLE_BYTES + 1):
        raise ValueError("ELF dynamic string table exceeds the verifier bound")
    string_table_offset = vaddr_to_file_offset(headers, string_table_vaddr[0])
    string_table = checked_slice(data, string_table_offset, string_table_size[0], "dynamic string table")
    soname = read_c_string(string_table, soname_offsets[0], "DT_SONAME")

    sections: dict[str, dict[str, Any]] = {}
    if section_header_count:
        if section_header_size != 64 or section_header_count > MAX_SECTION_HEADERS:
            raise ValueError("ELF section-header layout is outside the verifier bound")
        if section_name_index >= section_header_count:
            raise ValueError("ELF section-name string-table index is invalid")
        raw_sections = []
        for index in range(section_header_count):
            offset = section_header_offset + index * section_header_size
            raw = checked_slice(data, offset, section_header_size, f"section header {index}")
            raw_sections.append(struct.unpack("<IIQQQQIIQQ", raw))
        name_section = raw_sections[section_name_index]
        name_table = checked_slice(data, name_section[4], name_section[5], "section-name table")
        for row in raw_sections:
            name = read_c_string(name_table, row[0], "section name")
            if name in {".text", ".data", ".bss"}:
                sections[name] = {
                    "type": row[1],
                    "flagsValue": row[2],
                    "virtualAddress": row[3],
                    "fileOffset": row[4],
                    "size": row[5],
                }

    return {
        "sizeBytes": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
        "gnuBuildId": next(iter(build_ids)),
        "class": "ELF64",
        "endian": "little",
        "machine": "AArch64" if machine == 183 else f"EM_{machine}",
        "type": "ET_DYN" if elf_type == 3 else f"ET_{elf_type}",
        "entryPoint": entry_point,
        "soname": soname,
        "loadSegments": [
            {
                "fileOffset": header.offset,
                "virtualAddress": header.virtual_address,
                "fileSize": header.file_size,
                "memorySize": header.memory_size,
                "flags": flag_text(header.flags),
                "alignment": header.alignment,
            }
            for header in load_headers
        ],
        "sections": sections,
    }


def compare(actual: dict[str, Any], expected: dict[str, Any]) -> list[str]:
    mismatches: list[str] = []

    def require(label: str, actual_value: Any, expected_value: Any) -> None:
        if actual_value != expected_value:
            mismatches.append(f"{label}: actual={actual_value!r}, expected={expected_value!r}")

    source = expected["source"]
    elf = expected["elf"]
    require("source.basename", source["basename"], "liblibGameApp.so")
    require("source.sizeBytes", actual["sizeBytes"], int(source["sizeBytes"]))
    require("source.sha256", actual["sha256"], source["sha256"].lower())
    require("source.gnuBuildId", actual["gnuBuildId"], source["gnuBuildId"].lower())
    require("elf.class", actual["class"], elf["class"])
    require("elf.endian", actual["endian"], elf["endian"])
    require("elf.machine", actual["machine"], elf["machine"])
    require("elf.type", actual["type"], elf["type"])
    require("elf.entryPoint", actual["entryPoint"], parse_expected_int(elf["entryPoint"]))
    require("elf.soname", actual["soname"], elf["soname"])

    expected_loads = expected["loadSegments"]
    require("loadSegments.count", len(actual["loadSegments"]), len(expected_loads))
    for index, (actual_row, expected_row) in enumerate(zip(actual["loadSegments"], expected_loads)):
        for key in ("fileOffset", "virtualAddress", "fileSize", "memorySize", "alignment"):
            require(
                f"loadSegments[{index}].{key}",
                actual_row[key],
                parse_expected_int(expected_row[key]),
            )
        require(f"loadSegments[{index}].flags", actual_row["flags"], expected_row["flags"])

    section_types = {1: "PROGBITS", 8: "NOBITS"}
    for name, expected_section in expected.get("sections", {}).items():
        actual_section = actual["sections"].get(name)
        if actual_section is None:
            mismatches.append(f"sections.{name}: missing")
            continue
        require(
            f"sections.{name}.type",
            section_types.get(actual_section["type"], str(actual_section["type"])),
            expected_section["type"],
        )
        # NOBITS sections do not consume bytes from the file.  The identity
        # artifact therefore calls the section-header sh_offset a
        # ``reportedFileOffset`` for .bss, while older/ordinary section rows
        # use ``fileOffset``.  Accept either spelling without weakening the
        # value comparison.
        expected_file_offset = expected_section.get(
            "fileOffset", expected_section.get("reportedFileOffset")
        )
        if expected_file_offset is None:
            mismatches.append(f"sections.{name}.fileOffset: expected value is missing")
        else:
            require(
                f"sections.{name}.fileOffset",
                actual_section["fileOffset"],
                parse_expected_int(expected_file_offset),
            )
        for actual_key, expected_key in (
            ("virtualAddress", "virtualAddress"),
            ("size", "size"),
        ):
            require(
                f"sections.{name}.{actual_key}",
                actual_section[actual_key],
                parse_expected_int(expected_section[expected_key]),
            )

    loads = actual["loadSegments"]
    file_backed_end = max(row["fileOffset"] + row["fileSize"] for row in loads)
    virtual_end = max(row["virtualAddress"] + row["memorySize"] for row in loads)
    bss_start = actual["sections"][".bss"]["virtualAddress"]
    derived = expected["derivedLoadRelativeOffsets"]
    page_size = int(derived["pageSizeBytes"])
    require("derived.bssSectionStart", bss_start, parse_expected_int(derived["bssSectionStart"]))
    require(
        "derived.anonymousBss4kAnchor",
        align_up(bss_start, page_size),
        parse_expected_int(derived["anonymousBss4kAnchor"]),
    )
    require("derived.loadMemoryEnd", virtual_end, parse_expected_int(derived["loadMemoryEnd"]))
    require("derived.loadPageEnd", align_up(virtual_end, page_size), parse_expected_int(derived["loadPageEnd"]))
    require(
        "derived.fieldLoadRelativeOffset",
        align_up(bss_start, page_size) + parse_expected_int(derived["fieldOffsetFromAnonymousBssAnchor"]),
        parse_expected_int(derived["fieldLoadRelativeOffset"]),
    )
    expected_file_backed_end = parse_expected_int(expected["sections"][".bss"]["reportedFileOffset"])
    require("derived.loadFileBackedEnd", file_backed_end, expected_file_backed_end)
    return mismatches


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("elf", type=Path)
    parser.add_argument("--identity-json", type=Path, required=True)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    expected = json.loads(args.identity_json.read_text(encoding="utf-8"))
    actual = parse_elf(args.elf)
    mismatches = compare(actual, expected)
    report = {
        "schemaVersion": 1,
        "verified": not mismatches,
        "input": str(args.elf.resolve()),
        "identityArtifact": str(args.identity_json.resolve()),
        "observedAt": datetime.now(timezone.utc).isoformat(),
        "actual": {
            **{key: value for key, value in actual.items() if key not in {"entryPoint"}},
            "entryPoint": hex_value(actual["entryPoint"]),
            "loadSegments": [
                {
                    **row,
                    **{
                        key: hex_value(row[key])
                        for key in ("fileOffset", "virtualAddress", "fileSize", "memorySize", "alignment")
                    },
                }
                for row in actual["loadSegments"]
            ],
        },
        "mismatches": mismatches,
    }
    rendered = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8", newline="\n")
    print(rendered, end="")
    return 0 if report["verified"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
