# Fixtures

Extractor-export JSON documents used to seed the demo and tests. They are
hand-authored (the app never parses real ELF/Mach-O/PE) but shaped exactly like
real extractor output and carry extractor provenance.

| file | platform/arch | scenario |
|---|---|---|
| linux-x86_64-1.0.0.json / 1.1.0.json | linux/x86_64 | reserved-slot reuse, middle insertion, private tail field, tail-padding elision, varargs/calling convention, ZST, bitfield, alias rename, added/removed symbols |
| windows-x86_64-1.0.0.json / 1.1.0.json | windows/x86_64 | __stdcall -> __fastcall change, dllimport visibility change, platform-name alias |
| macos-aarch64-1.0.0.json / 1.1.0.json | macos/aarch64 | weak alias rename (explicit evidence), struct align change |
