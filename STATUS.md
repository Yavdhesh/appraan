# Appraan — Status

Milestone 1: skeleton, target discovery (Attach API), live JFR pipeline, OS collector SPI, demo target, proof run.

## Done
- [x] Project layout created (`src/main/java/dev/appraan/{cli,attach,discover,jfr,metrics,os,demo}`), local git repo initialised.

## In progress
- [ ] Source code for M1 (CLI, attach, discovery, JFR streaming, metrics, OS SPI, demo app)
- [ ] build.sh / build.bat producing `dist/appraan.jar`

## Remaining
- [ ] README.md + DESIGN.md
- [ ] Proof run vs LoadApp, output in `proof/run1.log`
- [ ] Final commit

## Notes / blockers
- Sandbox: Java TCP loopback is intercepted, so NO JMX/RMI. Connection uses Attach API
  (local socket file) + `executeJCmd`, and local `.jfr` snapshot files parsed with `jdk.jfr.consumer`.
- `sun.tools.attach.HotSpotVirtualMachine` (needed for `executeJCmd`) lives in a non-exported
  package of `jdk.attach`; builds and runs therefore use
  `--add-exports jdk.attach/sun.tools.attach=ALL-UNNAMED`. The exact working run command is in README.md.
