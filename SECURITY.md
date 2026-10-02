# Security

- No credential/token/MS-password handling. No hidden telemetry or network requests.
- No RCE, no arbitrary downloaded code. Updates: signed releases + checksum verification.
- Crash logs must redact secrets. Config backups stay local.
- Report issues privately to the maintainers; do not file exploits against third-party servers.
- Out of scope and never implemented: auth bypass, server compromise, DoS, packet attacks, account/protection bypasses.
