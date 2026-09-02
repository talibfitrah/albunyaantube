import Foundation

/// Task 7, the Task 4 trap's ROOT cause: YouTube's itag-140 fMP4 declares the FULL duration in
/// `mvhd`/`mdhd` while also carrying every fragment, so AVFoundation reports ~2× (probed live
/// 2026-09-01: afinfo 8455.99 s vs `AVURLAsset` 16912.02 s — and
/// `AVURLAssetPreferPreciseDurationAndTimingKey` changes NOTHING; zeroing the two fields, the
/// shape the fMP4 spec itself prescribes for fragmented files, yields the exact 8455.99 s).
/// Runs once at save completion. Fragmented files only (`mvex` present) — a plain mp4's `mvhd`
/// is authoritative and must not be touched.
nonisolated enum FragmentedMP4Durations {
    /// How much of the file head is searched for `moov` (it sits right after `ftyp` in these
    /// files; a `moov` beyond this is left alone — fail-safe no-op, never a corrupted file).
    private static let headLimit = 4 * 1024 * 1024

    /// The absolute byte ranges of every `mvhd`/`mdhd` duration field inside a `moov` that also
    /// contains `mvex` — empty for non-fragmented (or unparseable) data. Pure, so the tests pin
    /// the box walk without AVFoundation.
    static func durationFieldRanges(in data: Data) -> [Range<Int>] {
        // Zero-base a slice so every offset below is both a Data index and a file offset.
        guard data.startIndex == 0 else { return durationFieldRanges(in: Data(data)) }
        var ranges: [Range<Int>] = []
        var fragmented = false

        func boxType(at offset: Int) -> String? {
            String(data: data[(offset + 4)..<(offset + 8)], encoding: .ascii)
        }

        // `depth` caps the recursion: real nesting is `moov/trak/mdia` deep, so a head that nests
        // `trak` inside `trak` (once per 8 bytes, ~500 k frames within `headLimit`) is not a movie
        // header — it is a stack overflow on the cooperative thread `normalize` runs on, and a hard
        // crash at save completion that repeats on every retry (security r1 P3-1).
        func walk(_ lower: Int, _ upper: Int, _ depth: Int = 0) {
            guard depth < 8 else { return }
            var offset = lower
            while offset + 8 <= upper {
                let size = Int(readUInt32(data, at: offset) ?? 0)
                guard size >= 8, offset + size <= upper, let type = boxType(at: offset) else { return }
                if type == "trak" || type == "mdia" {
                    walk(offset + 8, offset + size, depth + 1)
                }
                if type == "mvex" { fragmented = true }
                if type == "mvhd" || type == "mdhd", offset + 9 <= upper {
                    let version = data[offset + 8]
                    // v0: ver/flags(4) + creation(4) + modification(4) + timescale(4) → 4-byte
                    // duration at +24; v1: 8-byte creation/modification → 8-byte duration at +32.
                    let (start, length) = version == 1 ? (offset + 32, 8) : (offset + 24, 4)
                    if start + length <= offset + size {
                        ranges.append(start..<(start + length))
                    }
                }
                offset += size
            }
        }

        // Top level: only moov is entered; everything else (ftyp/sidx/moof/mdat) is stepped over.
        var offset = 0
        let upper = min(data.count, Self.headLimit)
        while offset + 8 <= upper {
            let size = Int(readUInt32(data, at: offset) ?? 0)
            guard size >= 8 else { break }
            if offset + size <= upper, boxType(at: offset) == "moov" {
                walk(offset + 8, offset + size)
                break   // one movie header per file
            }
            offset += size
        }
        return fragmented ? ranges : []
    }

    /// Zeroes the duration fields in place. Any failure (unreadable head, unwritable file,
    /// nothing fragmented) is a silent no-op — the file still plays, just with the 2× scrubber.
    static func normalize(at url: URL) {
        guard let handle = try? FileHandle(forUpdating: url),
              let head = try? handle.read(upToCount: headLimit) else { return }
        defer { try? handle.close() }
        for range in durationFieldRanges(in: head) {
            try? handle.seek(toOffset: UInt64(range.lowerBound))
            try? handle.write(contentsOf: Data(repeating: 0, count: range.count))
        }
    }

    private static func readUInt32(_ data: Data, at offset: Int) -> UInt32? {
        guard offset + 4 <= data.count else { return nil }
        let index = data.startIndex + offset
        return (UInt32(data[index]) << 24) | (UInt32(data[index + 1]) << 16)
            | (UInt32(data[index + 2]) << 8) | UInt32(data[index + 3])
    }
}
