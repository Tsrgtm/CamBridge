# Contributing

Open an issue with a reproducible problem or send a focused pull request. Include phone/Android/OBS versions and the camera route when reporting hardware behavior. Remove device serials, private paths, and tokens from logs.

Follow the existing Java/C++ structure. Preserve packet compatibility, the bounded transport behavior, and existing Android/OBS identifiers. Avoid adding frame queues that accumulate latency. Do not log camera frames or introduce network telemetry. Include meaningful regression coverage for transport or source behavior changes, run Build.ps1 and Test.ps1, and describe device tests separately from automated tests.

Contributions are accepted under GPL-2.0-or-later. Do not commit toolchains, build outputs, recordings, private signing keys, or third-party proprietary code. A production signing/release process and wider device/version testing are useful future improvements.