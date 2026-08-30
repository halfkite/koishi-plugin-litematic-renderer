# Litematic GPU Agent

Windows GUI and cloud Agent for the Minecraft 26.2 GPU renderer.

Build with `gradlew.bat clean test fatJar portableZip`. On first launch the Agent downloads the official Minecraft 26.2 client, assets, libraries, and Fabric Loader into its private application-data directory. No account or existing game installation is used.

Runtime data defaults to `%LOCALAPPDATA%\LitematicGpuAgent`. Set `LITEMATIC_GPU_AGENT_HOME` to an absolute directory before launching the Agent to use a portable or service-managed data directory.

The HTTP v1 listener defaults to loopback only. WebSocket v2 is an outbound connection configured in the GUI. Public `ws://` endpoints are supported but expose task data to network observers; use `wss://` when possible.
