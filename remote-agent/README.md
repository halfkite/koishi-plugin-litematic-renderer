# Remote GPU Agent

This process runs beside the local Minecraft 26.2 GPU client. It receives an authenticated render request, writes a QuickCraft render-bridge job, waits for the two PNG files, and returns them to Koishi.

Run it with Node 20 or newer:

```powershell
Copy-Item agent.config.example.json agent.config.json
node remote-render-agent.js --config agent.config.json
```

Keep `listenHost` set to `127.0.0.1`. Do not expose this port on the public Internet. Use an FRP STCP tunnel or a cloud-side FRP visitor to provide a private endpoint to the Koishi container. Configure the cloud plugin with that visitor URL and the same `sharedSecret`.

## FRP topology

Use the included `frpc-provider.toml.example` on the local computer and `frpc-visitor.toml.example` in a cloud Docker sidecar. The Koishi container then reaches `http://frpc-visitor:39080`; the Agent remains bound to local loopback. Only FRP port `7000` needs to be reachable on the cloud IP. `frpc-visitor` must not publish port `39080`.

There are two independent secrets:

- FRP `auth.token` plus STCP `secretKey` protect the private tunnel.
- Agent `sharedSecret` signs every render request with HMAC, timestamp, and one-time nonce.

Generate each with a password manager or `node -e "console.log(require('crypto').randomBytes(32).toString('base64url'))"`. Never reuse the FRP secrets as the Agent shared secret.

The Minecraft client must be running and its `quickcraft-render-bridge/status.json` must update within five seconds. Requests are serialized because one GPU client processes one bridge job at a time.
