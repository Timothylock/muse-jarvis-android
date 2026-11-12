# Vendored upstream SDK

- Source repo: `~/workspace/muse-gadget-sdk` (Muse Gadget SDK, Apache-2.0), package `linux/src/musegadget`
- Commit: `55cbf4822976592002ee05579fca5eb1f79c676b` (`55cbf48`)
- Status: **unmodified**. `src/main/python/musegadget/` is byte-identical to upstream (all `.py` files and `data/`).
  The upstream `LICENSE` is kept as `musegadget/LICENSE-upstream`. All our behavior changes live in
  `src/main/python/jarvis_bridge.py` (monkeypatches at runtime; never edit the vendored files).
- `ble_server.py` (dbus/gi) is shipped but must never be imported.

## Re-vendor

```sh
cd ~/workspace/muse-gadget-sdk && git checkout <new-commit>
D=<repo>/transport/sdk/src/main/python
rm -rf $D/musegadget && cp -r linux/src/musegadget $D/ && find $D -name __pycache__ -prune -exec rm -rf {} +
cp LICENSE $D/musegadget/LICENSE-upstream
diff -r linux/src/musegadget $D/musegadget -x LICENSE-upstream   # must print nothing
```

Then update the commit above, re-read `service.py`, `link_client.py`, `voice_call.py`, `voice_gate.py`
(the bridge relies on `Service._sleep/_current/serve`, `VoiceCall._reply_gate/advisory_error_count/ready`,
`config.load_json/save_json/delete_json`), and re-run the on-device self-test.
